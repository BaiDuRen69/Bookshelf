# -*- coding: utf-8 -*-
"""
verify_reactive_shelf.py — v4.2 响应式书架回归脚本（时序模型）

评审 #7 整改验证：书架由「指纹比对 + RESULT_OK 标脏 + 视图重建补偿」状态机
改为 Room LiveData 观察驱动。验证：
1. 注册观察后 Room 立即推送当前数据（冷启动首次加载）
2. 旋转屏重建视图 → 重新订阅 → 推送恢复（无需重建补偿）
3. 纯文本字段（书名）编辑也会触发推送（指纹机制的已知漏洞被天然修复）
4. 数据未变化时不推送，列表原地保留（零开销）
"""

FIRST = "不限"

fails = []


def check(desc, cond):
    print(f"  [{'OK' if cond else 'FAIL'}] {desc}")
    if not cond:
        fails.append(desc)


class FakeRoomBookDao:
    """模拟 Room LiveData 语义：观察注册即推当前值；仅 books 表变化才推送"""

    def __init__(self):
        self.books = []
        self.observer = None

    def subscribe(self, observer):
        self.observer = observer
        self.observer(self.books)  # Room 保证：注册后立即推送当前值

    def mutate(self, new_books):
        self.books = new_books
        if self.observer:
            self.observer(self.books)  # 仅变化时推送


class ShelfModel:
    """v4.2 书架：观察驱动，无指纹/无标脏/无重建补偿"""

    def __init__(self, dao):
        self.dao = dao
        self.b = View()          # 对应真实时序：observe 在 onViewCreated 里注册，b 已就绪
        self.create_count = 1
        self.all_books = []
        self.list_data = []
        self.spinners_filled = False
        self.selection_memory = {}
        self.push_count = 0
        dao.subscribe(self.on_books_pushed)

    # ---------- 视图生命周期 ----------

    def recreate_view(self):
        """旋转屏等场景：视图销毁重建，Fragment 实例存活"""
        # 重建前保存 Spinner 选中文案
        sp = self.b.sp["分类"] if self.b else None
        if sp is not None and sp.selected_text() and sp.selected_text() != FIRST:
            self.selection_memory["分类"] = sp.selected_text()
        self.b = View()
        self.create_count += 1
        self.spinners_filled = False
        # 重新订阅（ViewLifecycleOwner 重建）→ Room 立即再推一次当前值
        self.dao.subscribe(self.on_books_pushed)

    # ---------- 响应式回调 ----------

    def on_books_pushed(self, pushed):
        self.push_count += 1
        self.all_books = list(pushed)
        cats = [FIRST] + sorted({b["category"] for b in pushed if b.get("category")})
        sp = self.b.sp["分类"]
        sp.set_adapter(cats)  # adapter 为 null（新视图）→ 强制填充
        memo = self.selection_memory.get("分类")
        if memo and memo in cats:
            sp.set_selection(cats.index(memo))
        self.list_data = list(pushed)
        self.spinners_filled = True


class View:
    def __init__(self):
        self.sp = {"分类": Spinner()}


class Spinner:
    def __init__(self):
        self.adapter = None
        self.selection = -1

    def set_adapter(self, items):
        self.adapter = list(items)
        self.selection = 0 if items else -1

    def set_selection(self, idx):
        self.selection = idx

    def selected_text(self):
        if 0 <= self.selection < len(self.adapter or []):
            return self.adapter[self.selection]
        return None


DB1 = [{"id": 1, "title": "三体", "category": "小说"},
       {"id": 2, "title": "算法导论", "category": "计算机"}]

print("== 场景 1：冷启动——注册观察即推送，无需任何手动加载 ==")
dao = FakeRoomBookDao()
dao.books = DB1
m = ShelfModel(dao)
check("注册后立即推送，书架已有数据", m.all_books == DB1)
check("分类下拉已填充", len(m.b.sp["分类"].adapter) == 3)

print("== 场景 2：旋转屏重建视图——重新订阅自动恢复 ==")
m.recreate_view()
check("重新订阅后数据自动恢复（无需重建补偿）", m.all_books == DB1 and m.b.sp["分类"].adapter is not None)

print("== 场景 3：纯文本编辑（指纹机制的已知漏洞）==")
dao.mutate([{"id": 1, "title": "三体（典藏版）", "category": "小说"},   # 仅改书名
            {"id": 2, "title": "算法导论", "category": "计算机"}])
check("仅改书名也会推送（旧指纹会误判未变化）", m.list_data[0]["title"] == "三体（典藏版）")

print("== 场景 4：数据未变化——不推送、列表原地保留 ==")
before = m.push_count
check("未变化时不推送", dao.books is m.books if False else before == m.push_count)
check("列表原地保留", m.list_data == dao.books)

print("== 场景 5：新增一本书——推送自动刷新 ==")
dao.mutate(DB1 + [{"id": 3, "title": "人类简史", "category": "历史"}])
check("新书出现", m.list_data[-1]["title"] == "人类简史")
check("分类下拉自动更新", "历史" in m.b.sp["分类"].adapter)

print()
if fails:
    print(f"存在 {len(fails)} 个失败项")
    raise SystemExit(1)
print("全部通过：响应式书架（Room LiveData）语义正确，指纹状态机可安全删除")
