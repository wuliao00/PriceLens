// PriceLens 端侧 LLM 的最小 JNI：加载 GGUF、按 GBNF 约束解码、返回一段 JSON 文本。
//
// 为什么是"最小"：这一层的职责只有三件事 —— 把模型装进内存、按语法解码、把文本交回去。
// 槽位判定、置信、展示**都不在这里**：那些属于理解层（com.pricelens.coupon），
// 而且这一层今天的输出**必须**经过确定性解析器复核（见 docs/端侧AI兜底.md 的接缝契约）。
//
// 线程安全：一个 session 一次只跑一个请求（上层是单线程的兜底调用），不做内部加锁 ——
// 与其假装线程安全，不如让调用方在 Kotlin 侧串行化（那里已经有单飞逻辑）。
//
// 目标 ABI 只有 arm64-v8a：中长尾机型里 32 位 SDK 早就不在支持范围，多编一份只是白占体积。

#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr const char *kTag = "PriceLensLLM";

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_threads = 4;
    // 上一轮的产出速度，给"这模型在这台机器上跑不跑得动"提供可读证据
    double last_tokens_per_second = 0.0;
    int last_generated_tokens = 0;
};

void log_error(const std::string &msg) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", msg.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pricelens_coupon_ai_LlamaNative_nativeLoad(JNIEnv *env, jclass, jstring j_model_path, jint n_threads, jint n_ctx) {
    if (j_model_path == nullptr) return 0;
    const char *path_chars = env->GetStringUTFChars(j_model_path, nullptr);
    std::string path(path_chars == nullptr ? "" : path_chars);
    if (path_chars != nullptr) env->ReleaseStringUTFChars(j_model_path, path_chars);
    if (path.empty()) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // 纯 CPU：端侧兜底要的是"一定能跑"，不是最快
    // 权重走 mmap：低内存时内核可以回收页，而不是一次性把 400MB 全摁在 RSS 里
    // （这个字段在新版是枚举 load_mode，不再是 use_mmap 布尔 —— 编译期就会告诉我们）
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        log_error("模型加载失败：" + path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    // 只算最后一个 token 的 logits：抽取任务不需要整段，省一大块内存
    cparams.n_batch = static_cast<uint32_t>(n_ctx);

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        log_error("上下文创建失败（模型可能太大或 n_ctx 超了训练上下文）");
        llama_model_free(model);
        return 0;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_threads = n_threads;
    __android_log_print(ANDROID_LOG_INFO, kTag, "模型已加载：%s n_ctx=%d threads=%d", path.c_str(), n_ctx, n_threads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_pricelens_coupon_ai_LlamaNative_nativeRun(JNIEnv *env, jclass, jlong handle, jstring j_prompt, jstring j_grammar, jint max_tokens) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->ctx == nullptr) return nullptr;

    const char *prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_chars == nullptr ? "" : prompt_chars);
    if (prompt_chars != nullptr) env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    std::string grammar;
    if (j_grammar != nullptr) {
        const char *grammar_chars = env->GetStringUTFChars(j_grammar, nullptr);
        grammar.assign(grammar_chars == nullptr ? "" : grammar_chars);
        if (grammar_chars != nullptr) env->ReleaseStringUTFChars(j_grammar, grammar_chars);
    }

    // 每次请求都从干净 KV 开始：兜底层是"一段文案进、一段 JSON 出"，不做多轮对话
    llama_memory_clear(llama_get_memory(session->ctx), true);

    // 分词。先按估算开缓冲，不够再按返回值扩容（llama_tokenize 返回负数时是所需长度）
    std::vector<llama_token> tokens(prompt.size() + 8);
    int32_t n_tokens = llama_tokenize(session->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n_tokens < 0) {
        tokens.resize(static_cast<size_t>(-n_tokens) + 8);
        n_tokens = llama_tokenize(session->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (n_tokens <= 0) {
        log_error("分词失败或空 prompt");
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(n_tokens));

    const int n_ctx = static_cast<int>(llama_n_ctx(session->ctx));
    if (n_tokens >= n_ctx) {
        log_error("prompt 超过上下文长度，直接放弃（不做截断：截断会悄悄改变输入语义）");
        return nullptr;
    }

    // 采样链：语法约束在最前面，后面只留贪心（temperature=0 等价物）。
    // 顺序即语义：grammar 负责"只能吐合法 JSON"，它之后的采样器只是从候选里挑最大概率那个。
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(sparams);
    if (!grammar.empty()) {
        llama_sampler *grammar_sampler = llama_sampler_init_grammar(session->vocab, grammar.c_str(), "root");
        if (grammar_sampler == nullptr) {
            log_error("GBNF 语法解析失败 —— 宁可失败也不静默退回无约束解码（那会产出不合 schema 的 JSON）");
            llama_sampler_free(chain);
            return nullptr;
        }
        llama_sampler_chain_add(chain, grammar_sampler);
    }
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    // prompt 先整体喂进去
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(session->ctx, batch) != 0) {
        log_error("prompt decode 失败");
        llama_sampler_free(chain);
        return nullptr;
    }

    std::string out;
    int generated = 0;
    const int64_t t_start = ggml_time_us();
    const int32_t limit = max_tokens > 0 ? max_tokens : 256;
    const int32_t room = n_ctx - static_cast<int32_t>(tokens.size()) - 1;

    while (generated < limit && generated < room) {
        const llama_token sampled = llama_sampler_sample(chain, session->ctx, -1);
        llama_sampler_accept(chain, sampled);
        if (llama_vocab_is_eog(session->vocab, sampled)) break;

        char piece[256];
        const int32_t piece_len = llama_token_to_piece(session->vocab, sampled, piece, sizeof(piece), 0, false);
        if (piece_len > 0) out.append(piece, static_cast<size_t>(piece_len));
        generated += 1;

        // llama_batch_get_one 收的是**可写**指针（它会把位置信息填进去），
        // 所以这里必须是一个非 const 的局部变量，不能把上面那个 const 直接传进去
        llama_token next_token = sampled;
        llama_batch next = llama_batch_get_one(&next_token, 1);
        if (llama_decode(session->ctx, next) != 0) {
            log_error("生成中 decode 失败");
            break;
        }
    }

    const int64_t t_end = ggml_time_us();
    session->last_generated_tokens = generated;
    session->last_tokens_per_second = generated > 0 ? generated * 1e6 / static_cast<double>(t_end - t_start) : 0.0;
    llama_sampler_free(chain);

    __android_log_print(ANDROID_LOG_INFO, kTag, "生成完成：%d tokens, %.2f tok/s", generated, session->last_tokens_per_second);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_pricelens_coupon_ai_LlamaNative_nativeLastStats(JNIEnv *env, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return nullptr;
    char buf[128];
    snprintf(buf, sizeof(buf), "tokens=%d tok_per_s=%.2f", session->last_generated_tokens, session->last_tokens_per_second);
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_pricelens_coupon_ai_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr) llama_free(session->ctx);
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}

}  // extern "C"
