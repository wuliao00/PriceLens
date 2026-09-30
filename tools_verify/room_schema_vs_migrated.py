#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Room 侧 schema 一致性核对（结构性，不是 identity hash）：
把 Room 导出的 v3 schema JSON（临时 exportSchema=true + ksp room.schemaLocation 生成）
与"MIGRATION_2_3 跑完之后的 sqlite 库"逐列、逐索引对一遍。

为什么需要它：本模块没有 Robolectric / room-testing，运行时那套
TableInfo 校验一次都没跑过 —— 只验 SQL 本身不代表 Room 认这笔账。
Room 校验的粒度就是"列名/类型/NOT NULL/默认值/主键位 + 索引名/唯一性/列序"，
这里按同一套口径离线对一遍（**默认值是字符串原样比对，Room 也正是这么比的**）。

跑法：py -X utf8 tools_verify/room_schema_vs_migrated.py
（需要 app/schemas/**/3.json 存在：那是临时开 exportSchema 时 ksp 产出的，用完即删）
"""
import json
import os
import re
import sqlite3
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from migration_2_3_check import V2_DDL, DIRTY, migration_sql_concat  # 同一份 SQL，不重抄  # noqa: E402

ROOT = os.path.dirname(HERE)
SCHEMA = os.path.join(ROOT, "app", "schemas", "com.pricelens.data.local.AppDatabase", "3.json")
TABLE = "price_history"

FAILURES = []


def check(name, ok, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + detail) if detail else ""))
    if not ok:
        FAILURES.append(name)


def migrated_db():
    path = os.path.join(tempfile.gettempdir(), "pl_room_schema_check.sqlite")
    if os.path.exists(path):
        os.remove(path)
    conn = sqlite3.connect(path)
    for ddl in V2_DDL:
        conn.execute(ddl)
    conn.executemany(
        "INSERT INTO price_history (id, productId, date, price, isLowest, isHighest) VALUES (?,?,?,?,0,0)",
        DIRTY,
    )
    for stmt in migration_sql_concat():
        conn.execute(stmt)
    conn.commit()
    return path, conn


def norm_default(value):
    """Room 记的默认值与 sqlite 记的默认值都要归一到同一种写法再比"""
    if value is None:
        return None
    text = str(value).strip()
    if text == "" or text.upper().startswith("NULL"):
        return None
    return text


def main():
    print("=" * 78)
    print("Room v3 schema JSON  vs  迁移后的实际库结构")
    print("=" * 78)
    if not os.path.exists(SCHEMA):
        print("缺少 %s：先在临时开 exportSchema=true 的构建里跑一次 :app:kspDebugKotlin" % SCHEMA)
        sys.exit(2)
    with open(SCHEMA, encoding="utf-8") as fh:
        schema = json.load(fh)
    db_json = schema["database"]
    print("Room 导出：version=%s identityHash=%s" % (db_json["version"], db_json["identityHash"]))
    room = next(e for e in db_json["entities"] if e["tableName"].lower() == TABLE)
    path, conn = migrated_db()

    room_cols = {c["columnName"]: c for c in room["fields"]}
    db_cols = {r[1]: r for r in conn.execute("PRAGMA table_info(%s)" % TABLE).fetchall()}
    print("\n[1] 字段：Room 期望 %d 个，库里 %d 个" % (len(room_cols), len(db_cols)))
    check("字段集合完全一致", set(room_cols) == set(db_cols),
          "Room-only=%s db-only=%s" % (sorted(set(room_cols) - set(db_cols)), sorted(set(db_cols) - set(room_cols))))
    for name, rc in sorted(room_cols.items()):
        dc = db_cols.get(name)
        if dc is None:
            continue
        same_type = str(rc["affinity"]).upper() == str(dc[2]).upper()
        same_notnull = bool(rc.get("notNull", False)) == bool(dc[3])
        same_default = norm_default(rc.get("defaultValue")) == norm_default(dc[4])
        check(
            "%s：类型/NOT NULL/默认值" % name,
            same_type and same_notnull and same_default,
            "room=(%s,%s,%s) db=(%s,%s,%s)" % (rc["affinity"], rc.get("notNull"), rc.get("defaultValue"), dc[2], dc[3], dc[4])
        )
    # Room 全新安装用的 CREATE TABLE 与迁移后的库必须逐列同形（否则"新装 vs 老用户升级"两套结构）
    print("\n[1b] Room 全新安装的 createSql vs 迁移后库的列默认值")
    print("     room: %s" % room["createSql"].replace("${TABLE_NAME}", TABLE))
    fresh_defaults = dict(re.findall(r"`(\w+)` [A-Z]+ NOT NULL(?: DEFAULT ([^,)]+))?", room["createSql"]))
    for name, value in sorted(fresh_defaults.items()):
        db_default = norm_default(db_cols.get(name, [None] * 5)[4])
        check("新装列 %s 的默认值与迁移后一致" % name,
              norm_default(value) == db_default, "%s vs %s" % (value, db_default))
    pk = [r[1] for r in conn.execute("PRAGMA table_info(%s)" % TABLE).fetchall() if r[5]]
    room_pk = room["primaryKey"]["columnNames"]
    check("主键一致（仍是自增 id）", pk == room_pk, "%s vs %s" % (pk, room_pk))
    check("Room 侧记的是自增主键", room["primaryKey"].get("autoGenerate") is True)
    autoinc = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='%s'" % TABLE).fetchone()[0]
    check("AUTOINCREMENT 位保留（Room 的 PK 位=1 才对）", "AUTOINCREMENT" in autoinc.upper())

    print("\n[2] 索引")
    room_idx = {i["name"]: i for i in room["indices"]}
    db_idx = {}
    for r in conn.execute("PRAGMA index_list(%s)" % TABLE).fetchall():
        cols = [c[2] for c in conn.execute("PRAGMA index_info(%s)" % r[1]).fetchall()]
        db_idx[r[1]] = (bool(r[2]), cols)
    check("索引名集合一致", set(room_idx) == set(db_idx), "room=%s db=%s" % (sorted(room_idx), sorted(db_idx)))
    for name, ri in sorted(room_idx.items()):
        expected_cols = ri["columnNames"]
        di = db_idx.get(name)
        if di is None:
            continue
        check(
            "%s：唯一性与列序" % name,
            bool(ri.get("unique", False)) == di[0] and [c.lower() for c in expected_cols] == [c.lower() for c in di[1]],
            "room=(unique=%s,%s) db=(unique=%s,%s)" % (ri.get("unique"), expected_cols, di[0], di[1])
        )
    uniq = [n for n, v in db_idx.items() if v[0]]
    check("确实存在 (productId,date) 上的唯一索引", any(v[0] and sorted(c.lower() for c in v[1]) == ["date", "productid"] for v in db_idx.values()), str(uniq))

    print("\n[3] 迁移后的数据形态（顺带再确认一遍）")
    rows = conn.execute("SELECT COUNT(*), COUNT(DISTINCT productId || date) FROM price_history").fetchone()
    check("一天一行", rows[0] == rows[1], "%d 行 / %d 天" % rows)
    check("老行出处仍是未记录",
          conn.execute("SELECT COUNT(*) FROM price_history WHERE source<>'UNRECORDED'").fetchone()[0] == 0)
    conn.close()
    os.remove(path)

    print("\n" + "=" * 78)
    if FAILURES:
        print("schema 核对失败 %d 项：%s" % (len(FAILURES), "、".join(FAILURES)))
        sys.exit(1)
    print("schema 结构核对通过（注意：仍未验证 Room 运行时的 identityHash）")


if __name__ == "__main__":
    main()
