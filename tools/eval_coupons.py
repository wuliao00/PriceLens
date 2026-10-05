#!/usr/bin/env python3
"""找券准确率评测量具（纯 Python 标准库；不参与 App 构建，不需要 gradle）。

做什么：把「golden 人工标注」与「pred（App/规则产出的券）」逐条对齐，按 source 输出
券级 P/R/F1 + slot 错误 TOP 榜，并在与 baseline 对比时决定 CI 红绿。

用法：
  py tools/eval_coupons.py --self-test                     # 量具自测（内置 6 条样本 + 手算断言）
  py tools/eval_coupons.py --lint tools/golden/coupons.jsonl  # 校验 golden 来源可查（反编造）
  py tools/eval_coupons.py golden.jsonl pred.jsonl [baseline.json]
        [--expect-perfect] [--require-source page_node]...
        [--write-baseline out.json] [--tolerance 0.01] [--report out.json]

判据（券级命中）：**面额 discount 与门槛 threshold 都精确相等**（双 null 视为相等）才算命中。
  - scope / state 不参与命中判定，只进 slot 错误榜当诊断（否则一个措辞差异会把 P/R 一起搅浑）；
  - threshold 的 0.0 与 null 是**不同**的两件事：0.0 = 文案显式写了「无门槛」，
    null = 文案没给门槛。把 null 当 0 会算出错误的到手价，所以不许混。

分母为空的口径（写死，别改）：
  tp+fp+fn == 0 → P/R/F1 全部 None，报告里显示 `n/a`（这个 source 本轮**不可判定**，不参与门禁）；
  tp == 0 但有预测或有漏 → F1 = 0.0（全错就是全错，不因"没门槛可召回"而虚高）；
  除此之外 F1 = 2PR/(P+R)。
  `--require-source X` 用于把"X 本该有券却变成 n/a"判成失败——防的是"把 golden 里某个 source
  删空之后自反性照样 1.0 全绿"这种静默成功。

退出码：
  0 通过；1 门禁红（相对 baseline 回退 > tolerance，或 --expect-perfect 未达标）；
  2 输入/数据错误（文件读不到、JSON 行坏、schema 不合、pred 出现 golden 里没有的 id、重复 id）；
  3 自测或 --lint 失败（量具自己不可信 / golden 来源不可查）。

设计约束：**不许静默成功**。任何被跳过的行、缺失的条目、读不到的文件都要显式计数并打出来。
"""

from __future__ import annotations

import argparse
import io
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

SOURCES = ("page_node", "clipboard", "community")
SLOTS = ("discount", "threshold", "scope", "state")
COUPON_KEYS = ("discount", "threshold", "scope", "state", "expiry", "url")
PRICE_KEYS = ("final", "list", "drop")
EXIT_OK, EXIT_GATE, EXIT_DATA, EXIT_SELFTEST = 0, 1, 2, 3

AMOUNT_RE = re.compile(r"\d+(?:\.\d+)?")


def _use_utf8_stdout() -> None:
    """Windows 控制台默认 GBK，中文报告要么乱码要么直接抛错；统一改写成 UTF-8。"""
    try:
        if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
            sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


# --------------------------------------------------------------------------
# 归一化与单条校验
# --------------------------------------------------------------------------


def amount(value, where: str, field: str, errors: list) -> "float | None":
    """面额/门槛只收 int/float（bool 不算）。字符串数字一律拒绝——会让 '5' 与 5.0 分家。"""
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        errors.append(f"{where}: {field}={value!r} 必须是数字或 null（不接受字符串 {type(value).__name__}）")
        return None
    return float(value)


def text(value, where: str, field: str, errors: list) -> "str | None":
    if value is None:
        return None
    if not isinstance(value, str):
        errors.append(f"{where}: {field}={value!r} 必须是字符串或 null")
        return None
    return value


def amount_key(value: "float | None") -> str:
    """精确相等的键：1500 与 1500.0 同键（同一个 float 的 repr），0.0 与 None 不同键。"""
    return "null" if value is None else repr(value)


def parse_coupon(obj, where: str, errors: list) -> dict:
    if not isinstance(obj, dict):
        errors.append(f"{where}: 券条目必须是对象，实际是 {type(obj).__name__}")
        return {}
    unknown = sorted(set(obj) - set(COUPON_KEYS))
    if unknown:
        errors.append(f"{where}: 券条目有未知字段 {unknown}（只允许 {list(COUPON_KEYS)}）")
    missing = sorted(set(COUPON_KEYS) - set(obj))
    if missing:
        errors.append(f"{where}: 券条目缺字段 {missing}（没有就用 null 写明，不许省略键）")
    return {
        "discount": amount(obj.get("discount"), where, "discount", errors),
        "threshold": amount(obj.get("threshold"), where, "threshold", errors),
        "scope": text(obj.get("scope"), where, "scope", errors),
        "state": text(obj.get("state"), where, "state", errors),
        "expiry": text(obj.get("expiry"), where, "expiry", errors),
        "url": text(obj.get("url"), where, "url", errors),
    }


def parse_entry(obj, where: str, is_golden: bool, errors: list) -> dict:
    if not isinstance(obj, dict):
        errors.append(f"{where}: 条目必须是对象，实际是 {type(obj).__name__}")
        return {}
    missing = [k for k in ("id", "source", "coupons") if k not in obj]
    if missing:
        errors.append(f"{where}: 缺必需字段 {missing}")
        return {}
    eid = obj.get("id")
    if not isinstance(eid, str) or not eid.strip():
        errors.append(f"{where}: id 必须是非空字符串")
        return {}
    source = obj.get("source")
    if source not in SOURCES:
        errors.append(f"{where}({eid}): source={source!r} 不在 {list(SOURCES)} 里")
        return {}
    coupons = obj.get("coupons")
    if not isinstance(coupons, list):
        errors.append(f"{eid}: coupons 必须是数组")
        return {}
    parsed = [parse_coupon(c, f"{eid}#券{i}", errors) for i, c in enumerate(coupons)]
    parsed = [c for c in parsed if c]

    price: dict = {k: None for k in PRICE_KEYS}
    has_price = "price" in obj
    if has_price:
        praw = obj.get("price")
        if not isinstance(praw, dict):
            errors.append(f"{eid}: price 必须是对象（或整段省略）")
        else:
            unknown = sorted(set(praw) - set(PRICE_KEYS))
            if unknown:
                errors.append(f"{eid}: price 有未知字段 {unknown}")
            price = {
                "final": amount(praw.get("final"), eid, "price.final", errors),
                "list": amount(praw.get("list"), eid, "price.list", errors),
                "drop": amount(praw.get("drop"), eid, "price.drop", errors),
            }

    raw = obj.get("raw")
    if raw is not None and not isinstance(raw, str):
        errors.append(f"{eid}: raw 必须是字符串或 null")
        raw = None
    if is_golden and (raw is None or raw == ""):
        errors.append(f"{eid}: golden 的 raw 必须是从夹具/测试里原样抄来的非空字符串")

    return {"id": eid, "source": source, "raw": raw or "", "coupons": parsed,
            "price": price, "has_price": has_price}


def load_jsonl(path: Path, is_golden: bool) -> "tuple[dict, list, dict]":
    """读 JSONL。返回 (按 id 索引的条目, 错误列表, 计数)。读不到文件直接抛错，由调用方转成退出码。"""
    errors: list = []
    counts = {"lines": 0, "bad_json": 0, "blank": 0, "dup_ids": 0}
    if not path.exists():
        return {}, [f"文件读不到：{path}"], counts
    if not path.is_file():
        return {}, [f"不是文件：{path}"], counts
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as exc:
        return {}, [f"{path} 不是 UTF-8（{exc}）"], counts
    except OSError as exc:
        return {}, [f"{path} 读取失败（{exc}）"], counts

    entries: dict = {}
    for lineno, line in enumerate(content.splitlines(), 1):
        stripped = line.strip()
        if not stripped:
            counts["blank"] += 1
            continue
        counts["lines"] += 1
        try:
            obj = json.loads(stripped)
        except json.JSONDecodeError as exc:
            counts["bad_json"] += 1
            errors.append(f"{path}:{lineno} 不是合法 JSON（{exc.msg}）")
            continue
        where = f"{path.name}:{lineno}"
        ent = parse_entry(obj, where, is_golden, errors)
        if not ent:
            continue
        if ent["id"] in entries:
            counts["dup_ids"] += 1
            errors.append(f"{path}:{lineno}: id 重复 {ent['id']}（同一个 id 只能出现一次，否则后面的会静默覆盖前面的）")
            continue
        entries[ent["id"]] = ent
    return entries, errors, counts


# --------------------------------------------------------------------------
# 打分
# --------------------------------------------------------------------------


def slot_equal(slot: str, a: dict, b: dict) -> bool:
    if slot in ("discount", "threshold"):
        return amount_key(a.get(slot)) == amount_key(b.get(slot))
    return a.get(slot) == b.get(slot)


def pair_and_diagnose(gold_left: list, pred_left: list, eid: str, slot_errors: Counter, examples: dict) -> None:
    """把"没精确命中"的两堆券做贪心配对，只为诊断（不影响 P/R/F1）。

    相似度 = 4 个槽里相等几个（null==null 记相等）。相似度 0 的配对不采纳：那是两张不同的券，
    把它们的差异说成"槽位错"会误导修模板的人。
    """
    cands = []
    for gi, g in enumerate(gold_left):
        for pi, p in enumerate(pred_left):
            sim = sum(1 for s in SLOTS if slot_equal(s, g, p))
            if sim > 0:
                cands.append((sim, gi, pi))
    # 相似度高的先配；同分按原序，保证同一对输入永远给同一份诊断（门禁要可复现）
    cands.sort(key=lambda c: (-c[0], c[1], c[2]))
    taken_g, taken_p = set(), set()
    for _sim, gi, pi in cands:
        if gi in taken_g or pi in taken_p:
            continue
        taken_g.add(gi)
        taken_p.add(pi)
        g, p = gold_left[gi], pred_left[pi]
        for s in SLOTS:
            if not slot_equal(s, g, p):
                slot_errors[s] += 1
                key = f"{fmt_val(s, g.get(s))} → {fmt_val(s, p.get(s))}"
                examples.setdefault(s, Counter())[key] += 1


def fmt_val(slot: str, v) -> str:
    if v is None:
        return "null"
    if slot in ("discount", "threshold"):
        return repr(float(v))
    return f"「{v}」"


def evaluate(golden: dict, pred: dict) -> dict:
    stats = {s: {"tp": 0, "fp": 0, "fn": 0} for s in SOURCES}
    overall = {"tp": 0, "fp": 0, "fn": 0}
    slot_errors: Counter = Counter()
    examples: dict = {}
    # 价格槽（final/list/drop）**不参与命中判定**：券级分数只由 discount+threshold 决定。
    # 但它们是用户看得见的东西，而 2026-10-05 之前整个量具对它们一个字都不说 ——
    # "#68 的 `实付低至` 判不出角色"就是这么藏了两周：单测里那句 assertNull 看着像口径，
    # 其实是没人测过。这里只出诊断榜，不改任何指标（自反性门禁与 baseline 格式都不受影响）。
    price_compared: Counter = Counter()
    price_diff: Counter = Counter()
    price_missing: Counter = Counter()   # 只有一侧有 price 段（pred 或 golden 没写）⇒ 没法比
    price_examples: dict = {}
    unknown_ids = []       # pred 里 golden 没有的 id（要报错，不能当"多检"混进指标）
    missing_entries = []   # golden 有、pred 整条没有（券全部算漏，但必须显式点名）
    source_mismatch = []   # 同 id 两边 source 不同（会让条目被算进另一个 source 的分母）
    matched_entries = 0

    for eid, g in golden.items():
        p = pred.get(eid)
        if p is None:
            missing_entries.append(eid)
            gc = Counter(amount_key(c["discount"]) + "|" + amount_key(c["threshold"]) for c in g["coupons"])
            stats[g["source"]]["fn"] += sum(gc.values())
            continue
        matched_entries += 1
        if p["source"] != g["source"]:
            source_mismatch.append(f"{eid}: golden={g['source']} pred={p['source']}")
        gkeys = [amount_key(c["discount"]) + "|" + amount_key(c["threshold"]) for c in g["coupons"]]
        pkeys = [amount_key(c["discount"]) + "|" + amount_key(c["threshold"]) for c in p["coupons"]]
        gc, pc = Counter(gkeys), Counter(pkeys)
        hit = gc & pc
        tp = sum(hit.values())
        fp = sum(pc.values()) - tp
        fn = sum(gc.values()) - tp
        stats[g["source"]]["tp"] += tp
        stats[g["source"]]["fp"] += fp
        stats[g["source"]]["fn"] += fn
        # 命中的那部分：discount/threshold 按定义相等，只可能错在 scope/state。
        # 这是"券命中但口径文案错"唯一的可见处——不做这步，scope/state 的错误永远进不了榜。
        def ckey(c):
            return amount_key(c["discount"]) + "|" + amount_key(c["threshold"])

        g_by_key: dict = {}
        p_by_key: dict = {}
        for c in g["coupons"]:
            g_by_key.setdefault(ckey(c), []).append(c)
        for c in p["coupons"]:
            p_by_key.setdefault(ckey(c), []).append(c)
        for k, n in hit.items():
            for i in range(n):
                gc_, pc_ = g_by_key[k][i], p_by_key[k][i]
                for s in ("scope", "state"):
                    if gc_.get(s) != pc_.get(s):
                        slot_errors[s] += 1
                        ex = f"{fmt_val(s, gc_.get(s))} → {fmt_val(s, pc_.get(s))}"
                        examples.setdefault(s, Counter())[ex] += 1

        # 没命中的那部分（各自的第 n 条之后）再贪心配对，诊断"面额/门槛错成了什么"
        g_left = [c for k, cs in g_by_key.items() for i, c in enumerate(cs) if i >= hit.get(k, 0)]
        p_left = [c for k, cs in p_by_key.items() for i, c in enumerate(cs) if i >= hit.get(k, 0)]
        if g_left and p_left:
            pair_and_diagnose(g_left, p_left, eid, slot_errors, examples)

        # 价格槽逐键比：`None` 也是一种取值（"这句没写到手价"与"到手价是 0"必须分得开，
        # 与门槛那个 0.0/null 的口径同一个道理）。两侧都没有 price 段时**不算比过**，
        # 免得"pred 没输出 price"被读成"价格全对"。
        for pk in PRICE_KEYS:
            if not (g.get("has_price") and p.get("has_price")):
                price_missing[pk] += 1
                continue
            gv, pv = g["price"].get(pk), p["price"].get(pk)
            price_compared[pk] += 1
            if gv != pv:
                price_diff[pk] += 1
                ex = f"{fmt_num(gv)} → {fmt_num(pv)}"
                price_examples.setdefault(pk, Counter())[f"{eid} {ex}"] += 1

    for eid, p in pred.items():
        if eid not in golden:
            unknown_ids.append(eid)
            stats[p["source"]]["fp"] += len(p["coupons"])

    # overall = 三个 source 的总和（含"pred 整条缺失"的漏检与"未知 id"的多检）。
    # 之前在这里单独累加会漏掉两类：missing_entries 的 FN、unknown_ids 的 FP —— 合计比各
    # source 加起来还少，等于把最该看见的两类错误从总榜里藏起来了。
    for s in SOURCES:
        for f in ("tp", "fp", "fn"):
            overall[f] += stats[s][f]

    result = {"sources": {}, "overall": {}, "slot_errors": slot_errors, "examples": examples,
              "price_compared": price_compared, "price_diff": price_diff,
              "price_missing": price_missing, "price_examples": price_examples,
              "unknown_ids": unknown_ids, "missing_entries": missing_entries,
              "source_mismatch": source_mismatch,
              "matched_entries": matched_entries, "counts": dict(stats)}
    for s in sorted(set(list(stats) + ["overall"])):
        c = stats[s] if s != "overall" else overall
        result[s if s != "overall" else "overall"] = metrics(c["tp"], c["fp"], c["fn"])
    return result


def metrics(tp: int, fp: int, fn: int) -> dict:
    """唯一的指标实现，self-test 直接对着这段手算。"""
    total = tp + fp + fn
    if total == 0:
        return {"tp": tp, "fp": fp, "fn": fn, "precision": None, "recall": None, "f1": None, "verdict": "n/a"}
    if tp == 0:
        return {"tp": tp, "fp": fp, "fn": fn,
                "precision": (0.0 if tp + fp > 0 else None),
                "recall": (0.0 if tp + fn > 0 else None),
                "f1": 0.0, "verdict": "zero"}
    p = tp / (tp + fp)
    r = tp / (tp + fn)
    return {"tp": tp, "fp": fp, "fn": fn, "precision": p, "recall": r,
            "f1": 2 * p * r / (p + r), "verdict": "ok"}


# --------------------------------------------------------------------------
# 报告 / 门禁
# --------------------------------------------------------------------------


def fmt_num(v) -> str:
    return "n/a" if v is None else f"{v:.4f}".rstrip("0").rstrip(".") if isinstance(v, float) else str(v)


def fmt_f1(v) -> str:
    return "n/a " if v is None else f"{v:.4f}"


def render(result: dict, golden_path: Path, pred_path: Path) -> list:
    lines = [f"找券评测：golden={golden_path}  pred={pred_path}"]
    lines.append("")
    lines.append(f"{'source':<12} {'TP':>4} {'FP':>4} {'FN':>4} {'P':>8} {'R':>8} {'F1':>8}")
    for s in SOURCES:
        m = result[s]
        lines.append(
            f"{s:<12} {m['tp']:>4} {m['fp']:>4} {m['fn']:>4} "
            f"{fmt_f1(m['precision']):>8} {fmt_f1(m['recall']):>8} {fmt_f1(m['f1']):>8}"
        )
    m = result["overall"]
    lines.append(f"{'— 合计 —':<10} {m['tp']:>4} {m['fp']:>4} {m['fn']:>4} "
                 f"{fmt_f1(m['precision']):>8} {fmt_f1(m['recall']):>8} {fmt_f1(m['f1']):>8}")
    lines.append("")
    lines.append(f"条目：golden 参与打分 {result['matched_entries']} 条；"
                 f"pred 里 golden 没有的 id {len(result['unknown_ids'])} 个；"
                 f"pred 整条缺失 {len(result['missing_entries'])} 个")
    if result["unknown_ids"]:
        lines.append("  ⚠ 未知 id（这些条目的预测券已按 FP 计入，但更可能是 pred 侧 id 漂了）："
                     + ", ".join(result["unknown_ids"][:20]))
    if result["missing_entries"]:
        lines.append("  ⚠ pred 里没有这些 golden id（其券全部按漏检计入）："
                     + ", ".join(result["missing_entries"][:20]))
    if result.get("source_mismatch"):
        lines.append("  ⚠ 同 id 两边 source 不一致（条目会被算进 golden 那一侧的 source 分母）："
                     + "; ".join(result["source_mismatch"][:10]))

    lines.append("")
    lines.append("[slot 错误 TOP 榜]（仅诊断，不参与命中判定；n/a=本轮该槽没有可对比的配对）")
    ranked = sorted(SLOTS, key=lambda s: (-result["slot_errors"].get(s, 0), s))
    for i, s in enumerate(ranked, 1):
        n = result["slot_errors"].get(s, 0)
        head = f" {i}. {s:<10} {n} 次"
        ex = result["examples"].get(s)
        if ex:
            top = ", ".join(f"{k} ×{c}" for k, c in ex.most_common(3))
            head += f"   错成什么：{top}"
        lines.append(head)

    if "price_compared" in result:
        lines.append("")
        lines.append("[价格槽对照]（仅诊断，不参与命中判定；口径：与 golden 精确相等，"
                     "None 也是一种取值 —— 「没写价格」与「价格是 0」必须分得开）")
        for pk in PRICE_KEYS:
            compared = result["price_compared"].get(pk, 0)
            diff = result["price_diff"].get(pk, 0)
            uncomparable = result["price_missing"].get(pk, 0)
            head = f"  {pk:<6} 一致 {compared - diff:>3} / 比对 {compared:>3}   不一致 {diff:>3}"
            if uncomparable:
                head += f"   没法比（一侧整段没写 price）{uncomparable}"
            lines.append(head)
            ex = result["price_examples"].get(pk)
            if ex:
                for k, c in ex.most_common(6):
                    lines.append(f"      · {k}" + (f" ×{c}" if c > 1 else ""))
    return lines


def to_baseline(result: dict, golden_path: Path, pred_path: Path) -> dict:
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "golden": str(golden_path),
        "pred": str(pred_path),
        "sources": {s: strip_metrics(result[s]) for s in SOURCES},
        "overall": strip_metrics(result["overall"]),
    }


def strip_metrics(m: dict) -> dict:
    return {k: m[k] for k in ("tp", "fp", "fn", "precision", "recall", "f1")}


def check_gate(baseline: dict, result: dict, tolerance: float) -> list:
    """任一 source 的 F1 相对 baseline 回退 > tolerance ⇒ 返回失败原因（非空即 exit 1）。"""
    fails = []
    src = baseline.get("sources") or {}
    if not isinstance(src, dict):
        return ["baseline 里 sources 不是对象，无法比对"]
    for s in SOURCES:
        b = src.get(s)
        if not isinstance(b, dict):
            fails.append(f"{s}: baseline 里没有这个 source 的记录（baseline 与当前 golden 的 source 集合不一致）")
            continue
        bf, cf = b.get("f1"), result[s]["f1"]
        if bf is None and cf is None:
            continue
        if bf is not None and cf is None:
            fails.append(f"{s}: F1 从 {bf:.4f} 变成不可判定（n/a）——通常是 golden/pred 把这个 source 抽空了")
            continue
        if bf is None and cf is not None:
            continue
        if cf < bf - tolerance:
            fails.append(f"{s}: F1 {bf:.4f} → {cf:.4f}，回退 {bf - cf:.4f} > 容差 {tolerance}")
    return fails


def check_perfect(result: dict, require_sources: list, data_errors: list) -> list:
    """自反性：golden vs golden 必须每个 source 都是 F1=1.0 或不可判定；有券的 source 必须满分。"""
    fails = []
    if data_errors:
        fails.append(f"存在 {len(data_errors)} 条数据错误，自反性不成立（先看错误清单）")
    if result["unknown_ids"]:
        fails.append(f"未知 id {len(result['unknown_ids'])} 个")
    if result["missing_entries"]:
        fails.append(f"缺失条目 {len(result['missing_entries'])} 个")
    for s in SOURCES:
        m = result[s]
        if m["f1"] is None:
            if s in require_sources:
                fails.append(f"{s}: 被要求有券可测，但本轮不可判定（tp+fp+fn=0）——golden 里这个 source 是空的")
            continue
        if m["f1"] != 1.0:
            fails.append(f"{s}: 自反性要求 F1=1.0，实际 {m['f1']:.4f}（tp={m['tp']} fp={m['fp']} fn={m['fn']}）"
                         "—— 同一个文件对自已不命中，说明 golden 里有重复 id 级别的脏数据或脚本判据错了")
    return fails


# --------------------------------------------------------------------------
# --lint：golden 的来源必须可查（反编造）
# --------------------------------------------------------------------------


def lint_golden(golden: dict, raw_lines: dict) -> list:
    """逐条核对：raw 是否真的逐字节存在于 origin.file 指向的文件里；金额是否出现在 raw 文本里。

    这条是"不许编造金额"的机器化保证：抄错一个字、金额凭印象写，都会在这里红。
    """
    fails = []
    for eid, ent in golden.items():
        origin = raw_lines.get(eid, {}).get("origin")
        note = raw_lines.get(eid, {}).get("note")
        if note is not None and not isinstance(note, str):
            fails.append(f"{eid}: note 必须是字符串")
        if not isinstance(origin, dict):
            fails.append(f"{eid}: 缺 origin 对象（格式：{{\"kind\":\"fixture|test\",\"file\":\"仓库相对路径\"}}）")
            continue
        rel = origin.get("file")
        if not isinstance(rel, str) or not rel:
            fails.append(f"{eid}: origin.file 必须是非空的仓库相对路径")
            continue
        fpath = (REPO_ROOT / rel).resolve()
        try:
            inside = fpath.is_file() and str(fpath).startswith(str(REPO_ROOT))
        except OSError:
            inside = False
        if not inside:
            fails.append(f"{eid}: origin.file 指向的文件不存在或在仓库外：{rel}")
            continue
        try:
            content = fpath.read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")
        except OSError as exc:
            fails.append(f"{eid}: 读 {rel} 失败（{exc}）")
            continue
        raw = ent["raw"].replace("\r\n", "\n")
        if raw not in content:
            fails.append(f"{eid}: raw 在 {rel} 里找不到逐字节相同的串（不许凭记忆改写文案）："
                         f"「{raw[:60]}{'…' if len(raw) > 60 else ''}」")
            continue
        tokens = {float(t.group(0)) for t in AMOUNT_RE.finditer(raw)}
        for c in ent["coupons"]:
            for slot in ("discount", "threshold"):
                v = c.get(slot)
                if v is None:
                    continue
                if v == 0.0 and slot == "threshold":
                    if not re.search(r"无门槛|免门槛|0\s*元门槛", raw):
                        fails.append(f"{eid}: threshold=0（显式无门槛）但 raw 里没有「无门槛」字样")
                    continue
                if v not in tokens:
                    fails.append(f"{eid}: 券的 {slot}={v} 这个金额没出现在 raw 里（编造金额？）")
            # scope/state 允许来自同一棵树的上下文（例如券行没写"国补"、同屏的价格行写了），
            # 但必须是那个 origin 文件里真出现过的字样，不能是我发明的标签。
            for slot in ("scope", "state"):
                v = c.get(slot)
                if v is not None and v not in content:
                    fails.append(f"{eid}: 券的 {slot}=「{v}」在 {rel} 里找不到原样字样（发明了口径？）")
        for pk in PRICE_KEYS:
            v = ent["price"].get(pk)
            if v is not None and v not in tokens:
                fails.append(f"{eid}: price.{pk}={v} 这个金额没出现在 raw 里（编造价格？）")
    return fails


# --------------------------------------------------------------------------
# 自测
# --------------------------------------------------------------------------

ST_GOLDEN = [
    # 1) 精确命中：满15减8 券
    {"id": "st-a1", "source": "page_node", "raw": "下单领取满15减8元优惠券",
     "coupons": [{"discount": 8.0, "threshold": 15.0, "scope": None, "state": None, "expiry": None, "url": None}],
     "price": {"final": 7.35, "list": 19.87, "drop": None}, "user_mark": None},
    # 2) 漏检：领后减 1500，pred 一条都没产
    {"id": "st-a2", "source": "page_node", "raw": "领后减¥1500 立即领",
     "coupons": [{"discount": 1500.0, "threshold": None, "scope": "国补", "state": "立即领", "expiry": None, "url": None}],
     "price": {"final": None, "list": None, "drop": None}, "user_mark": None},
    # 3) 槽错：面额抄成 80（threshold/scope/state 都对 ⇒ 会被配成一对，只记 discount 错）
    {"id": "st-a3", "source": "page_node", "raw": "【点击领取】¥70无门槛立减券",
     "coupons": [{"discount": 70.0, "threshold": 0.0, "scope": None, "state": "点击领取", "expiry": None, "url": None}],
     "price": {"final": None, "list": None, "drop": None}, "user_mark": None},
    # 4) 多检：剪贴板里凭空多一张 99-20
    {"id": "st-a4", "source": "clipboard", "raw": "3 ￥A1b2C3d4E5￥ 打开淘宝",
     "coupons": [{"discount": 5.0, "threshold": 5.9, "scope": None, "state": None, "expiry": None, "url": None}],
     "price": {"final": None, "list": None, "drop": None}, "user_mark": None},
    # 5) 命中但 scope 措辞不同：判据只看面额+门槛，所以这仍是 TP，只进诊断榜
    {"id": "st-a5", "source": "community", "raw": "下单领取满28减20元优惠券",
     "coupons": [{"discount": 20.0, "threshold": 28.0, "scope": "天猫", "state": None, "expiry": None, "url": None}],
     "price": {"final": 8.07, "list": 36.2, "drop": None}, "user_mark": None},
    # 6) 纯幻觉：golden 没券，pred 产了一张 500 无门槛
    {"id": "st-a6", "source": "community", "raw": "国家补贴：Apple iPhone Air 5G手机",
     "coupons": [], "price": {"final": None, "list": None, "drop": None}, "user_mark": None},
]

ST_PRED = [
    {"id": "st-a1", "source": "page_node", "coupons":
     [{"discount": 8.0, "threshold": 15.0, "scope": None, "state": None, "expiry": None, "url": None}]},
    {"id": "st-a3", "source": "page_node", "coupons":
     [{"discount": 80.0, "threshold": 0.0, "scope": None, "state": "点击领取", "expiry": None, "url": None}]},
    {"id": "st-a4", "source": "clipboard", "coupons":
     [{"discount": 5.0, "threshold": 5.9, "scope": None, "state": None, "expiry": None, "url": None},
      {"discount": 20.0, "threshold": 99.0, "scope": None, "state": None, "expiry": None, "url": None}]},
    {"id": "st-a5", "source": "community", "coupons":
     [{"discount": 20.0, "threshold": 28.0, "scope": "平台", "state": None, "expiry": None, "url": None}]},
    {"id": "st-a6", "source": "community", "coupons":
     [{"discount": 500.0, "threshold": 0.0, "scope": None, "state": None, "expiry": None, "url": None}]},
    # 一条 golden 里没有的 id，专门用来验证"未知 id 必须报错并计入 FP"
    {"id": "st-ghost", "source": "page_node", "coupons":
     [{"discount": 1.0, "threshold": None, "scope": None, "state": None, "expiry": None, "url": None}]},
]


def self_test() -> int:
    """内置样本 + 手算可核对的断言。跑法：py tools/eval_coupons.py --self-test"""
    _use_utf8_stdout()
    fails = []
    total = [0]

    def check(label: str, got, want) -> None:
        total[0] += 1
        if isinstance(want, float) and isinstance(got, (int, float)) and not isinstance(got, bool):
            ok = abs(got - want) < 1e-9
        else:
            ok = got == want
        shown = round(got, 6) if isinstance(got, float) else got
        print(f"  {'OK  ' if ok else 'FAIL'} {label}: got={shown} want={want}")
        if not ok:
            fails.append(label)

    print("[self-test] 样本：6 条 golden × 6 条 pred（含 1 漏、1 多、1 槽错、1 纯幻觉、1 ghost id）")
    g = {e["id"]: e for e in (parse_entry(e, f"st/{e['id']}", True, []) for e in ST_GOLDEN)}
    p = {e["id"]: e for e in (parse_entry(e, f"st/{e['id']}", False, []) for e in ST_PRED)}
    r = evaluate(g, p)

    # ---- page_node：手算 ----
    # TP=1(a1)；FP=1(a3 面额不同) + 1(st-ghost 未知 id，1 张券) = 2；FN=1(a2 漏) + 1(a3) = 2
    # P = 1/(1+2) = 0.3333…；R = 1/(1+2) = 0.3333…；F1 = 2·P·R/(P+R) = 1/3
    check("page_node.tp", r["page_node"]["tp"], 1)
    check("page_node.fp", r["page_node"]["fp"], 2)
    check("page_node.fn", r["page_node"]["fn"], 2)
    check("page_node.precision", r["page_node"]["precision"], 1 / 3)
    check("page_node.recall", r["page_node"]["recall"], 1 / 3)
    check("page_node.f1", r["page_node"]["f1"], 1 / 3)

    # ---- clipboard：TP=1 FP=1 FN=0 → P=0.5 R=1.0 F1 = 2·0.5·1/(1.5) = 2/3 ----
    check("clipboard.tp", r["clipboard"]["tp"], 1)
    check("clipboard.fp", r["clipboard"]["fp"], 1)
    check("clipboard.fn", r["clipboard"]["fn"], 0)
    check("clipboard.precision", r["clipboard"]["precision"], 0.5)
    check("clipboard.recall", r["clipboard"]["recall"], 1.0)
    check("clipboard.f1", r["clipboard"]["f1"], 2 / 3)

    # ---- community：a5 命中(1) + a6 幻觉 FP(1)；TP=1 FP=1 FN=0 → F1 = 2/3 ----
    check("community.tp", r["community"]["tp"], 1)
    check("community.fp", r["community"]["fp"], 1)
    check("community.fn", r["community"]["fn"], 0)
    check("community.f1", r["community"]["f1"], 2 / 3)

    # ---- 合计（=三个 source 相加）：TP=3 FP=4 FN=2 ----
    # P = 3/(3+4) = 3/7；R = 3/(3+2) = 3/5；F1 = 2TP/(2TP+FP+FN) = 6/(6+4+2) = 0.5
    check("overall.tp", r["overall"]["tp"], 3)
    check("overall.fp", r["overall"]["fp"], 4)
    check("overall.fn", r["overall"]["fn"], 2)
    check("overall.precision", r["overall"]["precision"], 3 / 7)
    check("overall.recall", r["overall"]["recall"], 3 / 5)
    check("overall.f1", r["overall"]["f1"], 0.5)

    # ---- slot 诊断榜 ----
    check("slot.discount", r["slot_errors"].get("discount", 0), 1)
    check("slot.threshold", r["slot_errors"].get("threshold", 0), 0)
    check("slot.scope", r["slot_errors"].get("scope", 0), 1)
    check("slot.state", r["slot_errors"].get("state", 0), 0)
    check("slot.discount 示例", list(r["examples"].get("discount", {}).keys()), ["70.0 → 80.0"])
    check("slot.scope 示例", list(r["examples"].get("scope", {}).keys()), ["「天猫」 → 「平台」"])
    check("未知 id 计数", r["unknown_ids"], ["st-ghost"])

    # ---- 判据边界：0.0 ≠ null；双 null 相等 ----
    e = []
    a = parse_coupon({"discount": 70.0, "threshold": 0.0, "scope": None, "state": None, "expiry": None, "url": None}, "x", e)
    b = parse_coupon({"discount": 70.0, "threshold": None, "scope": "别的", "state": "别的", "expiry": None, "url": None}, "y", e)
    c = parse_coupon({"discount": None, "threshold": None, "scope": None, "state": None, "expiry": None, "url": None}, "z", e)
    d = parse_coupon({"discount": None, "threshold": None, "scope": "别的", "state": None, "expiry": None, "url": None}, "w", e)
    check("合法券不产生错误", e, [])
    check("无门槛(0.0) 不等于 未给门槛(null)",
          amount_key(a["discount"]) + "|" + amount_key(a["threshold"]) == amount_key(b["discount"]) + "|" + amount_key(b["threshold"]), False)
    check("双 null 命中判据相等",
          amount_key(c["discount"]) + "|" + amount_key(c["threshold"]) == amount_key(d["discount"]) + "|" + amount_key(d["threshold"]), True)
    e2 = []
    parse_coupon({"discount": "8", "threshold": None, "scope": None, "state": None, "expiry": None, "url": None}, "字符串面额", e2)
    check("字符串面额被拒并显式报错", len(e2), 1)

    # ---- 分母为空：n/a 而不是 0（防"没券可测"被读成"全错"）----
    check("空 source → f1 n/a", metrics(0, 0, 0)["f1"], None)
    check("空 source → verdict", metrics(0, 0, 0)["verdict"], "n/a")
    check("tp=0 且有预测 → f1 0.0", metrics(0, 2, 0)["f1"], 0.0)
    check("tp=0 且有漏 → f1 0.0", metrics(0, 0, 3)["f1"], 0.0)

    # ---- 门禁：回退 > tolerance 才红 ----
    base = {"sources": {"page_node": {"tp": 3, "fp": 1, "fn": 1, "precision": 0.75, "recall": 0.75, "f1": 0.75},
                        "clipboard": {"tp": 0, "fp": 0, "fn": 0, "precision": None, "recall": None, "f1": None},
                        "community": {"tp": 2, "fp": 0, "fn": 0, "precision": 1.0, "recall": 1.0, "f1": 1.0}}}
    # page_node 本轮 (3,2,2) → P=0.6 R=0.6 F1=0.6；baseline 0.75 ⇒ 回退 0.15 > 0.01 ⇒ 必须恰好红 1 项
    cur = evaluate_all_from({"page_node": (3, 2, 2), "clipboard": (0, 0, 0), "community": (2, 0, 0)})
    check("门禁抓到 page_node 回退", len(check_gate(base, cur, 0.01)), 1)
    check("门禁对持平放行", check_gate(base, evaluate_all_from(
        {"page_node": (3, 1, 1), "clipboard": (0, 0, 0), "community": (2, 0, 0)}), 0.01), [])
    # 从"有分"掉成"不可判定"（典型成因：golden 里这个 source 被抽空）也算回退
    na_fails = check_gate(base, evaluate_all_from(
        {"page_node": (0, 0, 0), "clipboard": (0, 0, 0), "community": (2, 0, 0)}), 0.01)
    check("门禁抓到 F1 掉成 n/a", len(na_fails), 1)
    check("n/a 原因措辞", bool(re.search("不可判定", na_fails[0])), True)
    check("容差内的小回退不红（0.75→0.745：tp=149, fp+fn=102 ⇒ 298/400）", check_gate(base, evaluate_all_from(
        {"page_node": (149, 51, 51), "clipboard": (0, 0, 0), "community": (2, 0, 0)}), 0.01), [])
    check("边界：恰好回退 0.01 不算红（判据是 >，不是 ≥）⇒ 0.75→0.74=740/1000",
          check_gate(base, evaluate_all_from({"page_node": (370, 130, 130), "clipboard": (0, 0, 0),
                                             "community": (2, 0, 0)}), 0.01), [])
    check("边界：回退 0.0105 就红 ⇒ 0.75→298/403=0.7395",
          len(check_gate(base, evaluate_all_from({"page_node": (149, 52, 53), "clipboard": (0, 0, 0),
                                                 "community": (2, 0, 0)}), 0.01)), 1)

    # ---- 自反性 ----
    same = evaluate(g, g)
    check("自反性：同文件对比各 source 满分或 n/a", check_perfect(same, [], []), [])
    check("自反性：page_node 确有券可测", same["page_node"]["f1"], 1.0)
    # 全空 golden 时自反性"照样绿"——这正是 --require-source 存在的理由（写进断言防手滑删松）
    empty = evaluate_all_from({"page_node": (0, 0, 0), "clipboard": (0, 0, 0), "community": (0, 0, 0)})
    check("空 source 不 require 时自反性会通过（所以必须 require）", check_perfect(empty, [], []), [])
    check("require-source 抓出被抽空的 source", len(check_perfect(empty, ["page_node"], [])), 1)
    check("自反性抓不出脏判据以外的东西：有数据错误时报红",
          len(check_perfect(same, [], ["x 行坏"])), 1)

    # ---- 坏数据不许静默 ----
    errs = []
    parse_coupon({"discount": "8", "threshold": None, "scope": None, "state": None, "expiry": None, "url": None}, "坏行", errs)
    check("字符串面额显式报错", len(errs) >= 1, True)
    errs2 = []
    parse_coupon({"discount": 8.0, "threshold": None, "scope": None, "expiry": None, "url": None}, "缺字段", errs2)
    check("缺 state 字段显式报错", len(errs2) >= 1, True)
    errs3 = []
    parse_coupon({"discount": 8.0, "threshold": None, "scope": None, "state": None, "expiry": None, "url": None, "note": "x"}, "未知字段", errs3)
    check("券里未知字段显式报错", len(errs3) >= 1, True)

    # ---- 端到端：main() 的退出码（用临时文件，不进仓库）----
    import contextlib
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        gp, pp = Path(td) / "g.jsonl", Path(td) / "p.jsonl"
        gp.write_bytes(("\n".join(json.dumps(e, ensure_ascii=False) for e in ST_GOLDEN) + "\n").encode("utf-8"))
        pp.write_bytes(("\n".join(json.dumps(e, ensure_ascii=False) for e in ST_PRED) + "\n").encode("utf-8"))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code_ghost = main([str(gp), str(pp)])
        check("端到端：pred 有未知 id ⇒ 退出码 2（数据错误）", code_ghost, EXIT_DATA)
        with contextlib.redirect_stdout(buf):
            code_ok = main([str(gp), str(pp), "--allow-unknown-id"])
        check("端到端：显式放行未知 id ⇒ 退出码 0", code_ok, EXIT_OK)
        with contextlib.redirect_stdout(buf):
            code_miss = main([str(gp), str(Path(td) / "nonexistent.jsonl")])
        check("端到端：pred 文件读不到 ⇒ 退出码 2 且不作任何断言", code_miss, EXIT_DATA)
        broken = Path(td) / "broken.jsonl"
        broken.write_bytes(b'{"id":"x","source":"page_node","coupons":[]}\nnot-json-here\n')
        with contextlib.redirect_stdout(buf):
            code_broken = main([str(broken), str(broken), "--expect-perfect"])
        check("端到端：坏 JSON 行在自反性模式下 ⇒ 退出码 1（不自称满分）", code_broken, EXIT_GATE)

    print()
    if fails:
        print(f"[self-test] 失败 {len(fails)} 项：{fails}")
        print("[self-test] 量具自己不可信，评测结果一律作废。")
        return EXIT_SELFTEST
    print(f"[self-test] 全部 {total[0]} 项断言通过（每条都在注释里给了手算过程）")
    return EXIT_OK


def evaluate_all_from(counts: dict) -> dict:
    """用 (tp,fp,fn) 直接造一个 evaluate() 形状的结果，供门禁自测。"""
    out = {"slot_errors": Counter(), "examples": {}, "unknown_ids": [], "missing_entries": []}
    total = [0, 0, 0]
    for s in SOURCES:
        tp, fp, fn = counts.get(s, (0, 0, 0))
        out[s] = metrics(tp, fp, fn)
        total = [total[0] + tp, total[1] + fp, total[2] + fn]
    out["overall"] = metrics(*total)
    return out


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="找券准确率评测量具（券级命中＝面额与门槛都精确相等）",
        epilog="退出码：0 通过 / 1 门禁红 / 2 输入或数据错误 / 3 自测或 --lint 失败")
    parser.add_argument("golden", nargs="?", help="golden JSONL")
    parser.add_argument("pred", nargs="?", help="pred（待评测量）JSONL")
    parser.add_argument("baseline", nargs="?", help="baseline JSON；给了就按 F1 回退门禁")
    parser.add_argument("--self-test", action="store_true", help="跑内置样本的自测断言")
    parser.add_argument("--lint", metavar="GOLDEN", help="只校验 golden：来源可查 + 金额不是编的")
    parser.add_argument("--expect-perfect", action="store_true", help="自反性模式：每个 source 的 F1 必须是 1.0 或 n/a")
    parser.add_argument("--require-source", action="append", default=[], choices=SOURCES,
                        help="声明这个 source 本轮必须有券可测（n/a 即失败），可重复")
    parser.add_argument("--allow-unknown-id", action="store_true",
                        help="把 pred 里的未知 id 降级为警告（默认判数据错误，退出码 2）")
    parser.add_argument("--tolerance", type=float, default=0.01, help="F1 回退容差，默认 0.01")
    parser.add_argument("--write-baseline", metavar="OUT", help="把本轮指标写成 baseline JSON")
    parser.add_argument("--report", metavar="OUT", help="把完整报告另存为 UTF-8 文本（GBK 控制台下用）")
    args = parser.parse_args(argv)

    _use_utf8_stdout()

    if args.self_test:
        return self_test()

    if args.lint:
        golden, errors, counts = load_jsonl(Path(args.lint), is_golden=True)
        raw_lines = {}
        for line in Path(args.lint).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(obj, dict) and isinstance(obj.get("id"), str):
                raw_lines[obj["id"]] = obj
        lint_fails = lint_golden(golden, raw_lines) if not errors else []
        if errors:
            print(f"[lint] golden 有 {len(errors)} 条数据错误：")
            for e in errors[:40]:
                print(f"  - {e}")
            if len(errors) > 40:
                print(f"  … 还有 {len(errors) - 40} 条")
        if lint_fails:
            print(f"[lint] 来源核对失败 {len(lint_fails)} 项：")
            for f in lint_fails[:40]:
                print(f"  - {f}")
            if len(lint_fails) > 40:
                print(f"  … 还有 {len(lint_fails) - 40} 项")
        if not errors and not lint_fails:
            by_source = ", ".join(
                "%s=%d条/%d券" % (s,
                                  sum(1 for e in golden.values() if e["source"] == s),
                                  sum(len(e["coupons"]) for e in golden.values() if e["source"] == s))
                for s in SOURCES
            )
            print(f"[lint] 通过：{len(golden)} 条 golden，每条 raw 都逐字节存在于 origin.file，"
                  f"金额都能在 raw 里找到。按 source：" + by_source)
        return EXIT_SELFTEST if (errors or lint_fails) else EXIT_OK

    if not args.golden or not args.pred:
        parser.error("需要 golden 与 pred 两个文件（或使用 --self-test / --lint）")

    golden_path, pred_path = Path(args.golden), Path(args.pred)
    golden, g_errors, g_counts = load_jsonl(golden_path, is_golden=True)
    pred, p_errors, p_counts = load_jsonl(pred_path, is_golden=False)
    data_errors = [f"golden: {e}" for e in g_errors] + [f"pred: {e}" for e in p_errors]

    if not golden:
        print("[eval] golden 里一条可用条目都没有——这不是「0 分」，是没测到任何东西：")
        for e in data_errors[:20]:
            print(f"  - {e}")
        return EXIT_DATA

    result = evaluate(golden, pred)
    lines = render(result, golden_path, pred_path)

    # 未知 id 默认按"数据错误"处理（退出码 2）而不是静默计成 FP：pred 的 id 漂了却跑出个
    # 像样的 F1，比直接失败危险得多。确实要允许 pred 带额外条目时用 --allow-unknown-id 显式降级。
    if result["unknown_ids"] and not args.allow_unknown_id and not args.expect_perfect:
        data_errors.append(
            f"pred 里有 {len(result['unknown_ids'])} 个 golden 不存在的 id（前 10 个："
            + ", ".join(result["unknown_ids"][:10]) + "）—— 判为数据错误；确认要放行用 --allow-unknown-id")

    bad = {k: v for k, v in [("golden_bad_json", g_counts["bad_json"]), ("pred_bad_json", p_counts["bad_json"]),
                             ("golden_dup_ids", g_counts["dup_ids"]), ("pred_dup_ids", p_counts["dup_ids"]),
                             ("golden_blank", g_counts["blank"]), ("pred_blank", p_counts["blank"])] if v}
    if bad:
        lines.append("")
        lines.append("⚠ 显式计数（跳过即静默失败，这里全部点名）：" + ", ".join(f"{k}={v}" for k, v in bad.items()))
    if data_errors:
        lines.append("")
        lines.append(f"[数据错误 {len(data_errors)} 条]")
        lines.extend(f"  - {e}" for e in data_errors[:40])
        if len(data_errors) > 40:
            lines.append(f"  … 还有 {len(data_errors) - 40} 条")

    text = "\n".join(lines) + "\n"
    print(text, end="")
    if args.report:
        rp = Path(args.report)
        rp.parent.mkdir(parents=True, exist_ok=True)
        rp.write_bytes(text.encode("utf-8"))
        print(f"[eval] 报告已另存：{args.report}")

    if args.write_baseline:
        bp = Path(args.write_baseline)
        bp.parent.mkdir(parents=True, exist_ok=True)
        bp.write_bytes(
            (json.dumps(to_baseline(result, golden_path, pred_path), ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        print(f"[eval] baseline 已写：{args.write_baseline}")

    gate_fail = []
    if args.expect_perfect:
        gate_fail += check_perfect(result, args.require_source, data_errors)
    if args.baseline:
        bpath = Path(args.baseline)
        if not bpath.is_file():
            print(f"[eval] baseline 读不到：{bpath}")
            return EXIT_DATA
        try:
            baseline = json.loads(bpath.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            print(f"[eval] baseline 不可解析：{bpath}（{exc}）")
            return EXIT_DATA
        gate_fail += check_gate(baseline, result, args.tolerance)
        if gate_fail:
            print("[门禁] 相对 baseline 回退：")
            for f in gate_fail:
                print(f"  ✗ {f}")
        else:
            print(f"[门禁] 相对 {args.baseline} 无回退（容差 {args.tolerance}）")
    if args.expect_perfect and not gate_fail:
        print("[自反性] 通过：每个有券的 source 都是 F1=1.0")

    if data_errors and not args.expect_perfect:
        return EXIT_DATA
    if gate_fail:
        return EXIT_GATE
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
