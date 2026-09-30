#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
MIGRATION_2_3 的真机外验证：把 AppDatabase.kt 里**那一串 SQL 原文**抠出来，
在 sqlite3 上按旧实体的真实 DDL 建一个 v2 脏库，跑一遍，再逐条断言。

为什么要抠源码而不是在脚本里重抄一份 SQL：重抄的那份永远是对的，
代码里那份未必 —— "测试跑的 SQL 和代码里的 SQL 是两份"是这类迁移测试最常见的假绿。

运行：  py tools_verify/migration_2_3_check.py     （本机 python 是 Windows Store 存根，必须用 py）

跑过的检查：
 1. v2 脏库：同一天多行（v2 的 @Insert(REPLACE) 配自增主键，本来就防不住）
 2. 执行 MIGRATION_2_3_SQL：重复行被清成什么样、留下的是哪一条
 3. 唯一索引真的生效：再插一条"同日不同 id"必须失败
 4. 三个新列都在、NOT NULL、带 DEFAULT
 5. 既有行的 source 是「来源未记录」而不是慢慢买
 6. 顺序不可反：先建唯一索引（不先去重）必须失败
 7. 迁移 SQL 里的 DEFAULT 字面量与 Entities.kt 的 Kotlin 默认值、
    PriceSource.UNRECORDED_NAME 三者一致（Room 的 schema 校验就是比这个）
"""
import os
import re
import sqlite3
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = os.path.join(ROOT, "app", "src", "main", "java", "com", "pricelens", "data", "local", "AppDatabase.kt")
ENTITIES = os.path.join(ROOT, "app", "src", "main", "java", "com", "pricelens", "data", "local", "entity", "Entities.kt")
SAMPLE = os.path.join(ROOT, "app", "src", "main", "java", "com", "pricelens", "domain", "PriceSample.kt")

# v2 时 price_history 的真实结构：非唯一索引 + 自增主键（这就是"每天 1 点"从来没被真正约束过的原因）
V2_DDL = [
    "CREATE TABLE `price_history` ("
    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` TEXT NOT NULL, "
    "`date` TEXT NOT NULL, `price` REAL NOT NULL, `isLowest` INTEGER NOT NULL, `isHighest` INTEGER NOT NULL)",
    "CREATE INDEX `index_price_history_productId` ON `price_history` (`productId`)",
    "CREATE INDEX `index_price_history_date` ON `price_history` (`date`)",
]

# 脏数据：故意让同一天有多行，模拟 persistHistory 每轮全量重插 + 每 30 分钟一次的自采
DIRTY = [
    # (id, productId, date, price)
    (1, "jd:111", "2026-09-28", 100.0),
    (2, "jd:111", "2026-09-28", 95.0),
    (3, "jd:111", "2026-09-28", 98.0),
    (4, "jd:111", "2026-09-29", 120.0),
    (5, "jd:111", "2026-09-29", 118.0),
    (6, "jd:222", "2026-09-28", 59.0),
    (7, "jd:333", "2026-09-26", 10.0),
    (8, "jd:333", "2026-09-26", 11.0),
    (9, "jd:333", "2026-09-26", 12.0),
    (10, "jd:333", "2026-09-26", 13.0),
    (11, "jd:333", "2026-09-26", 14.0),
]

FAILURES = []


def check(name, ok, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + detail) if detail else ""))
    if not ok:
        FAILURES.append(name)


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def migration_sql_concat():
    """把 MIGRATION_2_3_SQL 里的字面量按语句边界（逗号）拼回一条一条的 SQL"""
    src = read(APPDB)
    block = re.search(r"val MIGRATION_2_3_SQL:\s*List<String>\s*=\s*listOf\((.*?)\n\s*\)\s*\n", src, re.S)
    body = block.group(1)
    # 逐字符扫描：字符串字面量、+ 连接、逗号分隔语句
    stmts, cur, i, n = [], [], 0, len(body)
    while i < n:
        ch = body[i]
        if ch == '"':
            j = i + 1
            buf = []
            while j < n:
                if body[j] == "\\":
                    buf.append(body[j + 1])
                    j += 2
                    continue
                if body[j] == '"':
                    break
                buf.append(body[j])
                j += 1
            cur.append("".join(buf))
            i = j + 1
            continue
        if ch == ",":
            stmts.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        i += 1
    if "".join(cur).strip():
        stmts.append("".join(cur).strip())
    return [s for s in stmts if s]


def new_db(with_data=True):
    path = os.path.join(tempfile.gettempdir(), "pl_migration_check_%d.sqlite" % os.getpid())
    if os.path.exists(path):
        os.remove(path)
    conn = sqlite3.connect(path)
    for ddl in V2_DDL:
        conn.execute(ddl)
    if with_data:
        conn.executemany(
            "INSERT INTO price_history (id, productId, date, price, isLowest, isHighest) VALUES (?,?,?,?,0,0)",
            DIRTY,
        )
        conn.execute("INSERT INTO sqlite_sequence VALUES ('price_history', 11)")
    conn.commit()
    return path, conn


def columns(conn):
    return {r[1]: r for r in conn.execute("PRAGMA table_info(price_history)").fetchall()}


def indexes(conn):
    return {r[1]: r for r in conn.execute("PRAGMA index_list(price_history)").fetchall()}


def main():
    print("=" * 78)
    print("MIGRATION_2_3 的 sqlite3 实跑（脚本 = 代码里那份 SQL，非重抄）")
    print("=" * 78)

    sql = migration_sql_concat()
    print("\n[0] 从 AppDatabase.kt 抠出的 %d 条语句：" % len(sql))
    for idx, stmt in enumerate(sql, 1):
        print("    %d. %s" % (idx, stmt.replace("`", "")))

    unrecorded = re.search(r'const val UNRECORDED_NAME\s*=\s*"([^"]+)"', read(SAMPLE)).group(1)
    entity_default = re.search(r"val source:\s*String\s*=\s*\"([^\"]+)\"", read(ENTITIES)).group(1)
    # Room 2.8 的 KSP 不从 Kotlin 初始化器推默认值：@ColumnInfo(defaultValue) 必须自己写，
    # 且要与 ALTER 里的 DEFAULT **逐字**相同（sqlite 报回来的 dflt_value 带引号）
    columninfo_default = re.search(r'@ColumnInfo\(defaultValue = "([^"]+)"\)\s*\n\s*val source:', read(ENTITIES)).group(1)
    alter_default = re.search(r"ADD COLUMN `source` TEXT NOT NULL DEFAULT '([^']*)'", sql[0]).group(1)
    print("\n[0b] PriceSource.UNRECORDED_NAME = %r，实体 Kotlin 默认值 = %r，@ColumnInfo = %r，ALTER DEFAULT = %r"
          % (unrecorded, entity_default, columninfo_default, alter_default))
    check("枚举哨兵与 Kotlin 默认值一致", unrecorded == entity_default, "%s vs %s" % (unrecorded, entity_default))
    check("@ColumnInfo(defaultValue) 与迁移 SQL 的 DEFAULT 逐字一致",
          columninfo_default == "'%s'" % alter_default, "%s vs '%s'" % (columninfo_default, alter_default))
    check("老行默认值不是 MANMANBUY", unrecorded != "MANMANBUY", unrecorded)

    print("\n[1] v2 脏库（同一天多行）")
    path, conn = new_db()
    before = conn.execute("SELECT COUNT(*) FROM price_history").fetchone()[0]
    days = conn.execute("SELECT COUNT(*) FROM (SELECT DISTINCT productId, date FROM price_history)").fetchone()[0]
    print("    行数 %d，(productId,date) 组合 %d" % (before, days))
    check("脏数据确实同日多行", before > days, "%d 行 / %d 天" % (before, days))
    check("v2 没有唯一索引", all(r[2] == 0 for r in indexes(conn).values()))
    check("v2 还没有 source/dayLow/recordedAt", not ({"source", "dayLow", "recordedAt"} & set(columns(conn))))

    print("\n[2] 执行迁移 SQL")
    for stmt in sql:
        conn.execute(stmt)
    conn.commit()

    after = conn.execute("SELECT COUNT(*) FROM price_history").fetchone()[0]
    print("    迁移前 %d 行 -> 迁移后 %d 行（删掉 %d 行）" % (before, after, before - after))
    survivors = conn.execute("SELECT id, productId, date, price FROM price_history ORDER BY productId, date").fetchall()
    for row in survivors:
        print("      留下 id=%d %s %s price=%s" % row)
    check("每个 (productId,date) 只剩一行", after == days, "%d vs %d" % (after, days))
    check(
        "留下的是每组 MAX(id)（当日最后一次插入）",
        {r[0] for r in survivors} == {3, 5, 6, 11},
        str(sorted(r[0] for r in survivors))
    )
    check(
        "保留行的价格就是那次观测的值",
        {(r[1], r[2]): r[3] for r in survivors} == {("jd:111", "2026-09-28"): 98.0, ("jd:111", "2026-09-29"): 118.0,
                                                    ("jd:222", "2026-09-28"): 59.0, ("jd:333", "2026-09-26"): 14.0}
    )

    print("\n[3] 唯一索引真的挡得住")
    idx = indexes(conn).get("index_price_history_productId_date")
    check("唯一索引 index_price_history_productId_date 存在", idx is not None)
    check("它是 UNIQUE", idx is not None and idx[2] == 1, str(idx))
    try:
        conn.execute("INSERT INTO price_history (id, productId, date, price, isLowest, isHighest, source, dayLow, recordedAt)"
                     " VALUES (999,'jd:111','2026-09-28',1.0,0,0,'SELF_WATCH',1.0,1)")
        check("再插一条同日不同 id 必须失败", False, "竟然插进去了")
    except sqlite3.IntegrityError as e:
        check("再插一条同日不同 id 必须失败", True, type(e).__name__ + ": " + str(e))
    conn.rollback()
    # REPLACE 才是一天后来的正常写法：同日覆盖而不是报错
    conn.execute("INSERT OR REPLACE INTO price_history (id, productId, date, price, isLowest, isHighest, source, dayLow, recordedAt)"
                 " VALUES (3,'jd:111','2026-09-28',97.0,0,0,'SELF_WATCH',88.0,1700000000000)")
    conn.commit()
    same_day = conn.execute("SELECT COUNT(*) FROM price_history WHERE productId='jd:111' AND date='2026-09-28'").fetchone()[0]
    check("INSERT OR REPLACE 同日只留一行", same_day == 1, str(same_day))
    conn.execute("DELETE FROM price_history WHERE productId='jd:111' AND date='2026-09-28'")
    conn.execute("INSERT INTO price_history (id, productId, date, price, isLowest, isHighest, source, dayLow, recordedAt)"
                 " VALUES (3,'jd:111','2026-09-28',98.0,0,0,'UNRECORDED',0.0,0)")
    conn.commit()

    print("\n[4] 三个新列：NOT NULL + DEFAULT")
    cols = columns(conn)
    for name, typ in (("source", "TEXT"), ("dayLow", "REAL"), ("recordedAt", "INTEGER")):
        c = cols.get(name)
        check("%s 列存在且 NOT NULL %s" % (name, typ), c is not None and c[2] == typ and c[3] == 1, str(c))
        check("%s 带 DEFAULT" % name, c is not None and c[4] is not None, str(c[4] if c else None))
    check("既有行的 source = 来源未记录，不是慢慢买",
          conn.execute("SELECT COUNT(*) FROM price_history WHERE source <> 'UNRECORDED'").fetchone()[0] == 0)
    check("dayLow 默认 0（读侧据此回退到收盘点，绝不把 0 当最低价）",
          conn.execute("SELECT COUNT(*) FROM price_history WHERE dayLow <> 0.0").fetchone()[0] == 0)
    check("recordedAt 默认 0", conn.execute("SELECT COUNT(*) FROM price_history WHERE recordedAt <> 0").fetchone()[0] == 0)
    # 新插入不带 source 的行也必须落到"来源未记录"
    conn.execute("INSERT INTO price_history (productId, date, price, isLowest, isHighest) VALUES ('jd:999','2026-10-01',9.0,0,0)")
    got = conn.execute("SELECT source, dayLow, recordedAt FROM price_history WHERE productId='jd:999'").fetchone()
    check("不带 source 的新行默认同样是来源未记录", got[0] == unrecorded, str(got))
    conn.close()

    print("\n[5] 顺序不可反：先去重再建唯一索引")
    path2, conn2 = new_db()
    try:
        conn2.execute("CREATE UNIQUE INDEX `index_price_history_productId_date` ON `price_history` (`productId`, `date`)")
        check("脏库上直接建唯一索引必须失败", False, "竟然建成了")
    except sqlite3.IntegrityError as e:
        check("脏库上直接建唯一索引必须失败", True, type(e).__name__ + ": " + str(e))
    conn2.close()

    print("\n[6] 空库（全新安装不走迁移，只验 SQL 本身能过）")
    path3, conn3 = new_db(with_data=False)
    for stmt in sql:
        conn3.execute(stmt)
    check("空表上迁移同样成立", conn3.execute("SELECT COUNT(*) FROM price_history").fetchone()[0] == 0)
    conn3.close()

    for p in (path, path2, path3):
        try:
            os.remove(p)
        except OSError:
            pass

    print("\n" + "=" * 78)
    if FAILURES:
        print("迁移验证失败 %d 项：%s" % (len(FAILURES), "、".join(FAILURES)))
        sys.exit(1)
    print("迁移验证全部通过")


if __name__ == "__main__":
    main()
