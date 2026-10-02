#!/usr/bin/env python3
"""GitHub Discussions 原始节点 → community.json（文档 §九 的仓库侧）。

输入：`gh api graphql ... --jq '.data.repository.discussions.nodes'` 的输出（JSON 数组，可能为 null）。
输出：`community.json`（schemaVersion 1）。

幂等：posts 没变化时保持原 generatedAt、且内容一致就不写文件——
配合 git-auto-commit 的"有变化才提交"，不会每小时产生空提交。
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone

MAX_POSTS = 50
MAX_TITLE = 200
MAX_EXCERPT = 200
MAX_REPLIES = 3
MAX_BODY = 200
MAX_NAME = 60


def clip(text, limit: int) -> str:
    collapsed = " ".join(str(text or "").split())
    if len(collapsed) <= limit:
        return collapsed
    return collapsed[:limit] + "…"


def iso_to_ms(iso) -> int:
    try:
        return int(datetime.fromisoformat(str(iso).replace("Z", "+00:00")).timestamp() * 1000)
    except (ValueError, TypeError):
        return 0


def convert(nodes) -> dict:
    posts = []
    for node in (nodes or [])[:MAX_POSTS]:
        if not isinstance(node, dict):
            continue
        title = clip(node.get("title"), MAX_TITLE)
        url = str(node.get("url") or "").strip()
        if not title or not url.startswith("http"):
            continue
        replies = []
        comments = ((node.get("comments") or {}).get("nodes") or [])[:MAX_REPLIES]
        for comment in comments:
            if not isinstance(comment, dict):
                continue
            replies.append(
                {
                    "author": clip((comment.get("author") or {}).get("login"), MAX_NAME) or "匿名",
                    "body": clip(comment.get("body"), MAX_BODY),
                }
            )
        posts.append(
            {
                "title": title,
                "url": url,
                "category": clip((node.get("category") or {}).get("name"), 40) or "讨论",
                "author": clip((node.get("author") or {}).get("login"), MAX_NAME) or "匿名",
                "updatedAtMs": iso_to_ms(node.get("updatedAt")),
                "excerpt": clip(node.get("body"), MAX_EXCERPT),
                "replies": replies,
            }
        )
    posts.sort(key=lambda p: p["updatedAtMs"], reverse=True)
    return {"schemaVersion": 1, "generatedAt": "", "posts": posts}


def main() -> None:
    raw_path = sys.argv[1] if len(sys.argv) > 1 else "community_raw.json"
    out_path = sys.argv[2] if len(sys.argv) > 2 else "community.json"

    with open(raw_path, encoding="utf-8") as fh:
        nodes = json.load(fh)

    result = convert(nodes)

    old = None
    try:
        with open(out_path, encoding="utf-8") as fh:
            old = json.load(fh)
    except (OSError, json.JSONDecodeError):
        old = None

    if old is not None and old.get("posts") == result["posts"] and old.get("generatedAt"):
        result["generatedAt"] = old["generatedAt"]
    else:
        result["generatedAt"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    try:
        with open(out_path, encoding="utf-8") as fh:
            if fh.read() == text:
                print("[format_community] 无变化，未写入")
                return
    except OSError:
        pass

    # 字节写（\\n 原样落盘）：让 diff 与内容一一对应，不受 Windows 文本模式影响
    with open(out_path, "wb") as fh:
        fh.write(text.encode("utf-8"))
    print(f"[format_community] 已写 {out_path}（{len(result['posts'])} 条）")


if __name__ == "__main__":
    main()
