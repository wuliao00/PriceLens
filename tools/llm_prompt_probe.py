#!/usr/bin/env python3
"""PC 侧提示词探针：用同一份资产（prompt + GBNF）和同一个 GGUF，在电脑上先跑一遍。

为什么需要它：手机上一轮验证要"装包 → 推模型 → 广播 → 拉结果"（几分钟一轮），
而调 prompt / 调语法是"改一行看一次"的活。先在 PC 上把**语义**调对，
手机那轮就只剩"ABI 与速度"两件事需要证明。

**同一份资产**：只读 `app/src/main/assets/ai/coupon_prompt_v1.txt` 与 `coupon_schema.gbnf`，
本脚本不复制、不改写它们的内容。

与 Kotlin 侧的对应关系（这三行必须与 `LlamaRuntime` 保持同步，否则 PC 上跑出来的东西
不能代表 App 里跑的）：
  1. 剥掉 `#` 起始的元数据行、trim、把唯一的 `{text}` 换成待抽文本  ← AiPromptAsset.render
  2. 包成一次对话轮：`<|im_start|>user\\n` + 正文 + `\\n/no_think\\n<|im_end|>\\n<|im_start|>assistant\\n`  ← LlamaRuntime.chatWrap
  3. 用 grammar 文件约束解码，temperature=0                                      ← LlamaNative.nativeRun

用法：
  py tools/llm_prompt_probe.py --llama-cli <llama-cli.exe> --model <x.gguf> --text "满199减50"
  py tools/llm_prompt_probe.py ... --golden 20        # 直接把 golden 前 N 条过一遍，人眼扫槽位
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PROMPT_ASSET = REPO_ROOT / "app/src/main/assets/ai/coupon_prompt_v1.txt"
GBNF_ASSET = REPO_ROOT / "app/src/main/assets/ai/coupon_schema.gbnf"
GOLDEN = REPO_ROOT / "tools/golden/coupons.jsonl"

CHAT_PREFIX = "<|im_start|>user\n"
CHAT_SUFFIX = "\n/no_think\n<|im_end|>\n<|im_start|>assistant\n"


def render(text: str) -> str:
    """= AiPromptAsset.body + render（见文件头 1. 的同步要求）"""
    raw = PROMPT_ASSET.read_text(encoding="utf-8")
    body = "\n".join(line for line in raw.splitlines() if not line.strip().startswith("#")).strip()
    count = body.count("{text}")
    # 占位符不是恰好一次就炸掉：Kotlin 侧也是 check(count == 1)，两边口径必须一致
    if count != 1:
        raise SystemExit(f"prompt 资产里 {{text}} 出现 {count} 次（应为 1 次）——资产被改坏了")
    return body.replace("{text}", text)


def build_prompt(text: str) -> str:
    return CHAT_PREFIX + render(text) + CHAT_SUFFIX


def run_one(cli: str, model: str, prompt: str, max_tokens: int) -> str:
    """把 prompt 写进临时文件再用 `-f` 喂进去。

    为什么不直接 `-p <文本>`：Windows 上命令行参数走的是 ANSI 码页（这台机器是 cp936），
    Python 把中文参数交给子进程时编成 cp936 字节，而 llama.cpp 按 UTF-8 读 —— 模型看到的是乱码，
    症状是"抽取结果莫名其妙"，很容易被误判成"模型不行"。文件通道是 UTF-8 的，没这个问题。
    """
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as fh:
        fh.write(prompt)
        prompt_file = fh.name

    proc = subprocess.run(
        [
            cli, "-m", model, "-f", prompt_file, "--grammar-file", str(GBNF_ASSET),
            "-n", str(max_tokens), "--temp", "0", "--seed", "1",
            "-t", "4", "--log-disable",
        ],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        # stdin 必须显式断掉：llama-cli 生成完会进交互模式等输入，
        # 而我们是从脚本里调的（没有终端），它会**一直等下去**——上一轮就是卡在这里，
        # 表现为"命令超时且没有任何输出"，看起来像模型加载不动，其实是在等下一条输入。
        stdin=subprocess.DEVNULL,
    )
    Path(prompt_file).unlink(missing_ok=True)
    if proc.returncode != 0:
        return f"<llama-cli 退出码 {proc.returncode}>\n{proc.stderr[-500:]}"
    return proc.stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--llama-cli", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--text")
    ap.add_argument("--golden", type=int, default=0)
    ap.add_argument("--max-tokens", type=int, default=256)
    args = ap.parse_args()

    if args.golden:
        rows = [json.loads(l) for l in GOLDEN.read_text(encoding="utf-8").splitlines() if l.strip()]
        rows = [r for r in rows if r["coupons"]][: args.golden]
        for r in rows:
            out = run_one(args.llama_cli, args.model, build_prompt(r["raw"]), args.max_tokens)
            print(f"--- {r['id']} ({r['source']})")
            print(f"raw   : {r['raw'][:110]}")
            print(f"golden: {json.dumps(r['coupons'], ensure_ascii=False)}")
            print(f"model : {out[:400]}")
        return 0

    if not args.text:
        raise SystemExit("要么给 --text，要么给 --golden N")
    print(run_one(args.llama_cli, args.model, build_prompt(args.text), args.max_tokens))
    return 0


if __name__ == "__main__":
    sys.exit(main())
