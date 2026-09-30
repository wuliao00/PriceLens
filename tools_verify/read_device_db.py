#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
读一份从真机拉下来的 PriceLens 数据库，把「盯价自建曲线」相关的状态打印成可核对的证据。

配套取证命令（debug 包才可 run-as；**绝不要** adb uninstall，那会清掉用户的盯价目标）：

    adb exec-out run-as com.pricelens cat databases/pricelens.db      > /tmp/pldb/pricelens.db
    adb exec-out run-as com.pricelens cat databases/pricelens.db-wal  > /tmp/pldb/pricelens.db-wal

WAL 必须一起拉：Room 用 WAL journal，最近写入往往还在 -wal 里，
只拉 .db 会读到一个"看起来是空表"的旧快照（判空库要看 WAL）。

用法：py -X utf8 tools_verify/read_device_db.py <目录或 .db 路径>

打印：user_version / price_history 列与索引 / 每日点清单 / 按 source 的天数统计。
"""
import os
import sqlite3
import sys
import tempfile
import shutil


def resolve(path):
    if os.path.isdir(path):
        for name in ("pricelens.db", "price.db"):
            cand = os.path.join(path, name)
            if os.path.exists(cand):
                return cand
        dbs = [f for f in os.listdir(path) if f.endswith(".db")]
        if not dbs:
            raise SystemExit("目录里没有 .db 文件：%s" % path)
        return os.path.join(path, dbs[0])
    return path


def main():
    src = resolve(sys.argv[1] if len(sys.argv) > 1 else ".")
    # 复制到临时目录再打开：直接打开只读副本会被 SQLite 试图写 -shm/-wal 干扰，
    # 而原地打开又可能把手机侧的证据改掉。
    work = tempfile.mkdtemp(prefix="pldb")
    for suffix in ("", "-wal", "-shm"):
        if os.path.exists(src + suffix):
            shutil.copy2(src + suffix, os.path.join(work, os.path.basename(src) + suffix))
    db = os.path.join(work, os.path.basename(src))
    conn = sqlite3.connect(db)
    cur = conn.cursor()

    print("file:", src, "(%d bytes)" % os.path.getsize(src))
    wal = src + "-wal"
    print("wal :", "present %d bytes" % os.path.getsize(wal) if os.path.exists(wal) else "absent")
    print("user_version:", cur.execute("PRAGMA user_version").fetchone()[0])

    cols = cur.execute("PRAGMA table_info(price_history)").fetchall()
    if not cols:
        raise SystemExit("没有 price_history 表")
    names = [c[1] for c in cols]
    v3 = "source" in names
    print("\n== price_history 列 ==")
    for cid, name, typ, notnull, dflt, pk in cols:
        print("  %-12s %-8s notnull=%d default=%s pk=%d" % (name, typ, notnull, dflt, pk))
    print("== 索引 ==")
    for row in cur.execute("PRAGMA index_list(price_history)").fetchall():
        uniq = row[2]
        idx_cols = [r[2] for r in cur.execute("PRAGMA index_info(%s)" % repr(row[1])).fetchall()]
        print("  %-40s unique=%d cols=%s" % (row[1], uniq, idx_cols))
    if not v3:
        print("\n!! 这是 v2 库（没有 source/dayLow/recordedAt 三列，也没有 (productId,date) 唯一索引）")
        print("!! 下面的按出处统计/明细只在 v3 库上有意义，跳过。")
        rows = cur.execute("SELECT COUNT(*) FROM price_history").fetchone()[0]
        print("price_history 行数 = %d" % rows)
        for pid, date, price in cur.execute(
            "SELECT productId, date, price FROM price_history ORDER BY productId, date, id LIMIT 20"
        ).fetchall():
            print("   %s %s %.2f" % (pid, date, price))
        conn.close()
        shutil.rmtree(work, ignore_errors=True)
        return

    total = cur.execute("SELECT COUNT(*) FROM price_history").fetchone()[0]
    days = cur.execute("SELECT COUNT(*) FROM (SELECT productId, date FROM price_history GROUP BY 1,2)").fetchone()[0]
    print("\n行数=%d，(productId,date) 去重后=%d 天%s"
          % (total, days, "  <-- 同日多行，唯一索引没生效" if total != days else ""))

    print("\n== 按出处统计（天数）==")
    for src_name, d in cur.execute(
        "SELECT source, COUNT(DISTINCT date) FROM price_history GROUP BY source ORDER BY 2 DESC"
    ).fetchall():
        print("  %-14s %d 天" % (src_name, d))

    print("\n== 明细（每商品最近 20 个采样日）==")
    for pid, n in cur.execute(
        "SELECT productId, COUNT(*) FROM price_history GROUP BY productId ORDER BY 2 DESC"
    ).fetchall():
        print("  %s (%d 天)" % (pid, n))
        for date, price, low, source, rec in cur.execute(
            "SELECT date, price, dayLow, source, recordedAt FROM price_history "
            "WHERE productId=? ORDER BY date DESC LIMIT 20", (pid,)
        ).fetchall():
            flag = "  <-- dayLow=0：迁移前的老行，读侧回退到收盘点" if low == 0 else ""
            print("     %s close=%-9.2f low=%-9.2f %-12s rec=%d%s" % (date, price, low, source, rec, flag))
    conn.close()
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
