"""
书架页加载优化（v3.2）引入的三条新 SQL 的回归验证。

背景：BookDao 新增了
  - getDistinctCountries()  国家/地区去重下拉
  - getDistinctChannels()   来源渠道去重下拉
  - getContentFingerprint() 内容指纹（决定是否需要重载书架页）

这三条 SQL 直接决定书架页的加载速度与数据正确性，改动时务必跑一遍本脚本。
用内存 SQLite 复现同一套语句，无需 Android 环境。

运行：python tools/verify_shelf_sql.py
"""
import sqlite3
import sys

SCHEMA = ("CREATE TABLE books (id INTEGER PRIMARY KEY, country TEXT, sourceChannel TEXT, "
          "createdAt INTEGER, pages INTEGER, listPrice REAL, stockStatus INTEGER, "
          "readStatus INTEGER, ownType INTEGER, rating INTEGER)")

# 与 BookDao 中的 @Query 保持一致
Q_COUNTRIES = ("SELECT DISTINCT country FROM (SELECT TRIM(country) AS country FROM books "
               "WHERE country IS NOT NULL AND TRIM(country) != '') "
               "ORDER BY country ASC")
Q_CHANNELS = ("SELECT channel FROM (SELECT TRIM(sourceChannel) AS channel, "
              "MIN(createdAt) AS firstAt FROM books "
              "WHERE sourceChannel IS NOT NULL AND TRIM(sourceChannel) != '' "
              "GROUP BY TRIM(sourceChannel)) ORDER BY firstAt ASC")
Q_FINGERPRINT = ("SELECT COUNT(*) || ':' || IFNULL(MIN(createdAt), 0) || ':' "
                 "|| IFNULL(MAX(createdAt), 0) || ':' || IFNULL(SUM(id), 0) || ':' "
                 "|| IFNULL(SUM(pages), 0) || ':' "
                 "|| IFNULL(SUM(CAST(listPrice * 100 AS INTEGER)), 0) || ':' "
                 "|| IFNULL(SUM(stockStatus + readStatus + ownType + rating), 0) "
                 "FROM books")

# 刻意构造脏数据：前后空格、空串、null、重复渠道
SEED = [
    (1, "中国", "拼多多", 100, 300, 45.5, 0, 2, 0, 5),
    (2, " 中国", "淘宝", 200, 200, 30.0, 0, 1, 0, 4),   # 前导空格，应与第 1 条合并
    (3, "日本", "", 300, 250, 60.0, 0, 0, 0, 3),       # 空渠道，应排除
    (4, "", "京东", 400, 100, 20.0, 0, 2, 1, 2),       # 空国家，应排除
    (5, None, None, 500, 150, 25.0, 0, 0, 0, 1),       # null，应排除
    (6, "美国", "拼多多", 600, 180, 55.0, 0, 1, 2, 3),  # 与第 1 条同渠道
]

# 指纹必须能感知的变更类型（每项：名称、改动 SQL、还原 SQL）
FINGERPRINT_CASES = [
    ("新增", "INSERT INTO books VALUES (7,'英国','当当',700,120,35.0,0,0,0,5)",
     "DELETE FROM books WHERE id=7"),
    ("页数", "UPDATE books SET pages=999 WHERE id=1", "UPDATE books SET pages=300 WHERE id=1"),
    # 放大 100 倍的原因：CAST(45.5 AS INTEGER) 与 CAST(45.9 AS INTEGER) 同为 45，会漏掉角位调价
    ("定价-角位", "UPDATE books SET listPrice=45.9 WHERE id=1",
     "UPDATE books SET listPrice=45.5 WHERE id=1"),
    ("定价-元位", "UPDATE books SET listPrice=46.0 WHERE id=1",
     "UPDATE books SET listPrice=45.5 WHERE id=1"),
    ("定价-降价", "UPDATE books SET listPrice=45.0 WHERE id=1",
     "UPDATE books SET listPrice=45.5 WHERE id=1"),
    ("阅读状态", "UPDATE books SET readStatus=1 WHERE id=1", "UPDATE books SET readStatus=2 WHERE id=1"),
    ("藏书状态", "UPDATE books SET stockStatus=1 WHERE id=1", "UPDATE books SET stockStatus=0 WHERE id=1"),
    ("我的评分", "UPDATE books SET rating=1 WHERE id=1", "UPDATE books SET rating=5 WHERE id=1"),
    ("拥有类型", "UPDATE books SET ownType=1 WHERE id=1", "UPDATE books SET ownType=0 WHERE id=1"),
]


def main():
    db = sqlite3.connect(":memory:")
    db.execute(SCHEMA)
    db.executemany("INSERT INTO books VALUES (?,?,?,?,?,?,?,?,?,?)", SEED)
    problems = []

    def check(label, cond, detail=""):
        if cond:
            print("  [OK] %s %s" % (label, detail))
        else:
            problems.append("%s %s" % (label, detail))
            print("  [FAIL] %s %s" % (label, detail))

    print("== 1. 国家/地区去重（TRIM 后必须去重，不能有 '中国' 与 ' 中国' 两条）==")
    countries = [r[0] for r in db.execute(Q_COUNTRIES)]
    check("结果正确", countries == ["中国", "日本", "美国"], str(countries))
    check("无重复", len(countries) == len(set(countries)))

    print("== 2. 来源渠道去重（保持首次录入顺序）==")
    channels = [r[0] for r in db.execute(Q_CHANNELS)]
    check("结果正确", channels == ["拼多多", "淘宝", "京东"], str(channels))

    print("== 3. 内容指纹（决定书架页是否需要重载）==")
    base = db.execute(Q_FINGERPRINT).fetchone()[0]
    check("非 null", base is not None, str(base))
    for name, up, down in FINGERPRINT_CASES:
        db.execute(up)
        changed = db.execute(Q_FINGERPRINT).fetchone()[0] != base
        db.execute(down)
        restored = db.execute(Q_FINGERPRINT).fetchone()[0] == base
        check("%s 可感知且能复位" % name, changed and restored,
              "" if changed and restored else "感知=%s 复位=%s" % (changed, restored))

    print("== 4. 空表安全性（返回 null 会被误判为需重载）==")
    db.execute("DELETE FROM books")
    empty = db.execute(Q_FINGERPRINT).fetchone()[0]
    check("空表返回非 null", empty is not None, str(empty))

    db.close()
    print()
    if problems:
        print("发现 %d 个问题：" % len(problems))
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("全部通过：三条 SQL 的语法与语义均正确")


if __name__ == "__main__":
    main()
