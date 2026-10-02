#!/usr/bin/env python3
"""选择器规则清单生成器（仓库侧，与 update.json / APK sha256 同一套信任模型）。

做什么：
  1. 遍历 rules/*.json（不含 manifest.json），逐个校验 JSON 可解析、`id` 与文件名一致；
  2. 与 rules/manifest.json 里的旧 sha256 对比：
     - 内容变了 → 把规则文件自身的 `version` +1（写回文件），再重算 sha256；
     - 新文件（清单里没有）→ 沿用文件里的版本号，不改写文件；
  3. 重写 rules/manifest.json（manifestVersion 取时间戳格式 YYYYMMDDNN，保证严格递增）；
  4. 把每个规则文件原样复制到 app/src/main/assets/rules/（App 内置离线兜底规则，
     单一事实源仍是 rules/，assets 只是构建期快照 —— 单测 BuiltinRuleTest 会核对两者一致）。

幂等：没有任何内容变化时不写任何文件（CI 的 git-auto-commit 因此不会空提交，
rules/** 变更触发 workflow → 脚本无变化 → 不再触发，闭环）。

用法：
  python3 tools/gen_rules_manifest.py          # 正常生成
  python3 tools/gen_rules_manifest.py --check   # 只校验不写入（CI 可选门禁，退出码非 0 表示需要重新生成）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RULES_DIR = REPO_ROOT / "rules"
MANIFEST_PATH = RULES_DIR / "manifest.json"
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "rules"

SCHEMA_VERSION = 1
ID_RE = re.compile(r"^[a-z0-9_-]{1,32}$")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fail(msg: str) -> None:
    print(f"[gen_rules_manifest] 失败：{msg}", file=sys.stderr)
    sys.exit(1)


def load_old_manifest() -> dict:
    if not MANIFEST_PATH.exists():
        return {}
    try:
        return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"rules/manifest.json 不可解析（{exc}），先修复或删除它")


def main() -> None:
    parser = argparse.ArgumentParser(description="重算 rules/*.json 的 sha256 并写回 manifest.json")
    parser.add_argument("--check", action="store_true", help="只校验清单是否最新，不写文件")
    args = parser.parse_args()

    if not RULES_DIR.is_dir():
        fail(f"找不到规则目录 {RULES_DIR}")
    rule_files = sorted(p for p in RULES_DIR.glob("*.json") if p.name != "manifest.json")
    if not rule_files:
        fail("rules/ 下没有任何规则文件")

    old_manifest = load_old_manifest()
    old_by_id = {entry.get("id"): entry for entry in old_manifest.get("rules", [])}

    entries = []
    changed = False
    for path in rule_files:
        rule_id = path.stem
        if not ID_RE.match(rule_id):
            fail(f"{path.name}: 文件名必须是 [a-z0-9_-]，且与规则 id 一致")

        raw = path.read_bytes()
        try:
            obj = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            fail(f"{path.name}: 不是合法 JSON（{exc}）")
        if obj.get("id") != rule_id:
            fail(f"{path.name}: id={obj.get('id')!r} 与文件名不一致")
        version = obj.get("version")
        if not isinstance(version, int) or version < 1:
            fail(f"{path.name}: version 必须是 ≥1 的整数")

        old_entry = old_by_id.get(rule_id)
        if old_entry is None:
            # 新文件：沿用作者写的版本号，不改写内容；但清单需要新增该条目
            changed = True
        elif old_entry.get("sha256") != sha256_hex(raw):
            # 内容变了：自动递增 version（防止把新内容挂在旧版本号下），重写文件后重算 sha256
            version = int(old_entry.get("version", version)) + 1
            obj["version"] = version
            raw = (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            path.write_bytes(raw)
            changed = True
            print(f"[gen_rules_manifest] {path.name}: 内容变化，version 递增为 {version}")

        entries.append(
            {
                "id": rule_id,
                "file": f"rules/{path.name}",
                "version": version,
                "sha256": sha256_hex(raw),
            }
        )

    # assets 快照：任何时候都与 rules/ 保持逐字节一致（幂等写，无变化时 git 无 diff）
    if not args.check:
        ASSETS_DIR.mkdir(parents=True, exist_ok=True)
        for path in rule_files:
            shutil.copyfile(path, ASSETS_DIR / path.name)

    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "manifestVersion": int(old_manifest.get("manifestVersion", 0) or 0),
        "generatedAt": old_manifest.get("generatedAt", ""),
        "repository": "https://gitee.com/wuliao11541/PriceLens",
        "rules": entries,
    }

    old_entries = old_manifest.get("rules")
    if old_entries != entries:
        changed = True
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d")
        candidate = int(stamp) * 100 + 1
        manifest["manifestVersion"] = max(candidate, manifest["manifestVersion"] + 1)
        manifest["generatedAt"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    old_text = MANIFEST_PATH.read_text(encoding="utf-8") if MANIFEST_PATH.exists() else None

    if args.check:
        if old_text != text:
            fail("rules/manifest.json 与规则文件不一致，请运行 tools/gen_rules_manifest.py 后提交")
        print("[gen_rules_manifest] --check 通过：清单与规则文件一致")
        return

    if changed or old_text != text:
        # 以字节写（\n 原样落盘）：清单/规则参与 sha256 校验，绝不能让 Windows 文本模式把行尾翻成 CRLF
        MANIFEST_PATH.write_bytes(text.encode("utf-8"))
        print(f"[gen_rules_manifest] 已写 rules/manifest.json（manifestVersion={manifest['manifestVersion']}）")
    else:
        print("[gen_rules_manifest] 无变化，未写入")


if __name__ == "__main__":
    main()
