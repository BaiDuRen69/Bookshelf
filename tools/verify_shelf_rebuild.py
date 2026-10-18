# -*- coding: utf-8 -*-
"""
verify_shelf_rebuild.py — v3.5 回归脚本（纯 Python 时序模型）

复现 v3.5 bug：MainActivity 用 replace() 切 Tab，每次回书架页都重建视图；
v3.2 的指纹优化在「数据没变」时直接 return，新视图的适配器/Spinner 全空
→ 书籍、分类、排序全部不显示（打开 API 设置后回书架必现）。

验证修复：onResume 检测 viewRebuilt → applyDataToView() 用实例级缓存重放
UI 填充（不查库），再异步校验指纹。
"""

FIRST = "不限"


class Spinner:
    """最小 Spinner 模型：adapter 为 None 表示全新未填充"""

    def __init__(self, name):
        self.name = name
        self.adapter = None  # None=空；否则是内容列表
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


class ShelfModel:
    """ShelfFragment 关键状态与生命周期时序（v3.5 修复版）"""

    def __init__(self, db_books):
        # ---- 实例级字段（跨视图重建存活）----
        self.all_books = []            # 上次查到的书
        self.fingerprint = None        # 上次加载时的指纹
        self.first_load_done = False
        self.pending_reload = False
        self.view_rebuilt = False
        self.spinners_filled = False
        self.selection_memory = {}     # 筛选选中态记忆（key=spinner 名）
        # ---- 视图级状态 ----
        self.b = None                  # 当前视图的 Spinner 集合
        self.list_data = []            # 当前 RecyclerView 展示的数据
        self.db_books = db_books       # 模拟数据库（指纹恒定，模拟「API 设置不改数据」）

    # ---------- 生命周期 ----------

    def on_create_view(self):
        """replace() 重建视图：新 Spinner 全空，实例字段不动"""
        # 重建前保存用户选中的筛选
        for name in ("分类", "标签", "地点", "来源", "国家"):
            sp = self.b.sp[name] if self.b else None
            if sp is None:
                continue
            cur = sp.selected_text()
            if cur and cur != FIRST:
                self.selection_memory[name] = cur
            else:
                self.selection_memory.pop(name, None)
        # 新视图
        self.b = View()
        self.spinners_filled = False
        self.view_rebuilt = True  # ★ v3.5 修复的关键标记
        return self.b

    def on_resume(self):
        if self.first_load_done and not self.pending_reload:
            if self.view_rebuilt:
                self.view_rebuilt = False
                self.apply_data_to_view()  # ★ v3.5：新视图必须至少喂一次数据
            # 指纹校验（这里指纹恒定不变 → 不触发 loadData）
            return
        self.pending_reload = False
        self.first_load_done = True
        self.view_rebuilt = False
        self.load_data()

    # ---------- 数据 ----------

    def load_data(self):
        """完整查库 + 填充（模拟 loadData）"""
        self.all_books = list(self.db_books)
        self.apply_data_to_view()

    def apply_data_to_view(self):
        """用实例级缓存给当前视图喂数据（不查库）— v3.5 新增"""
        if self.b is None:
            return
        cats = [FIRST] + sorted({b["category"] for b in self.all_books if b.get("category")})
        sp = self.b.sp["分类"]
        sp.set_adapter(cats)  # adapter==null → 强制填充
        memo = self.selection_memory.get("分类")
        if memo and memo in cats:
            sp.set_selection(cats.index(memo))
        # applyFilters：按分类 Spinner 的当前选中项过滤后提交适配器
        picked = sp.selected_text()
        if picked and picked != FIRST:
            self.list_data = [b for b in self.all_books if b.get("category") == picked]
        else:
            self.list_data = list(self.all_books)
        self.spinners_filled = True

    # ---------- 断言辅助 ----------

    def books_visible(self):
        return len(self.list_data)

    def category_options(self):
        return self.b.sp["分类"].adapter


class View:
    def __init__(self):
        self.sp = {"分类": Spinner("分类"), "标签": Spinner("标签"),
                   "地点": Spinner("地点"), "来源": Spinner("来源"),
                   "国家": Spinner("国家")}


DB = [{"id": 1, "title": "三体", "category": "小说"},
      {"id": 2, "title": "算法导论", "category": "计算机"},
      {"id": 3, "title": "人类简史", "category": "历史"}]

fails = []


def check(desc, cond):
    print(f"  [{'OK' if cond else 'FAIL'}] {desc}")
    if not cond:
        fails.append(desc)


# ===== 场景 1：首次加载 → 我的页开 API 设置 → 回书架（用户报告的 bug）=====
print("== 场景 1：首次加载 → 切到我的页开 API 设置 → 回书架（数据没变）==")
m = ShelfModel(DB)
m.on_create_view()
m.on_resume()
check("首次加载后书籍可见 3 本", m.books_visible() == 3)

m.on_create_view()   # 切到「我的」Tab 再切回来：视图被 replace 重建
m.on_resume()        # 指纹没变 → 不重新查库
check("回书架后书籍可见 3 本（v3.4 及之前是 0）", m.books_visible() == 3)
check("分类下拉已填充", len(m.category_options()) == 4)

# ===== 场景 2：筛选选中态在重建后保留 =====
print("== 场景 2：用户选了「小说」，开 API 设置后回来仍保留 ==")
m2 = ShelfModel(DB)
m2.on_create_view()
m2.on_resume()
sp = m2.b.sp["分类"]
sp.set_selection(m2.category_options().index("小说"))
m2.selection_memory["分类"] = "小说"

m2.on_create_view()  # 重建
m2.on_resume()
sp2 = m2.b.sp["分类"]
check("分类下拉已填充", sp2.adapter is not None)
check("选中态保留为「小说」", sp2.selected_text() == "小说")
check("列表按筛选显示 1 本", m2.books_visible() == 1)

# ===== 场景 3：切 Tab 多次往返（重复重建）=====
print("== 场景 3：书架→我的→书架→我的→书架 连续往返 ==")
m3 = ShelfModel(DB)
m3.on_create_view()
m3.on_resume()
for _ in range(2):
    m3.on_create_view()
    m3.on_resume()
check("多次往返后书籍仍可见 3 本", m3.books_visible() == 3)
check("多次往返后分类下拉仍正常", len(m3.category_options()) == 4)

print()
if fails:
    print(f"存在 {len(fails)} 个失败项")
    raise SystemExit(1)
print("全部通过：v3.5 视图重建后必喂数据，指纹优化保留")
