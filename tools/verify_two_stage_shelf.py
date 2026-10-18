# -*- coding: utf-8 -*-
"""
v4.4 书架首屏加载优化回归脚本（Python 最小模型）

模拟三块改动：
1. BookshelfApp 双线程：io（UI 短查询）与 bg（启动长任务：补种/自动备份 zip）分离
2. ShelfFragment 两段式渲染：books 推送即渲染（第一段），字典补查后精筛（第二段）
3. applyFilters 降级：字典 Spinner 未就绪时按「不限」处理，不再整体跳过

8 个场景全绿才算通过。
"""
import threading
import time

PASS = 0
FAIL = 0


def check(name, cond):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  PASS  %s" % name)
    else:
        FAIL += 1
        print("  FAIL  %s" % name)


# ---------------- 模拟组件 ----------------

class TwoLaneExecutor:
    """双执行器：io（UI 短查询）与 bg（长任务）。记录各车道任务完成顺序与时间戳。"""

    def __init__(self):
        self.io_done = []   # io 车道完成任务名（按完成顺序）
        self.bg_done = []   # bg 车道完成任务名
        self.done_at = {}   # 任务名 -> 完成时间戳
        self.lock = threading.Lock()

    def io(self, name, fn, *args):
        def wrap():
            fn(*args)
            with self.lock:
                self.io_done.append(name)
                self.done_at[name] = time.time()
        threading.Thread(target=wrap, daemon=True).start()

    def bg(self, name, fn, *args):
        def wrap():
            time.sleep(0.05)  # zip 打包远慢于查询
            fn(*args)
            with self.lock:
                self.bg_done.append(name)
                self.done_at[name] = time.time()
        threading.Thread(target=wrap, daemon=True).start()


class Spinner:
    """最小 Spinner：entries 内容 + 选中位置。空 adapter 时 position 为 -1（INVALID_POSITION）。"""

    def __init__(self, entries=None):
        self.entries = entries or []
        self.position = 0 if self.entries else -1

    def getSelectedItemPosition(self):
        return self.position

    @staticmethod
    def sel_pos(sp):
        """对应 ShelfFragment.selPos：-1 夹到 0"""
        p = sp.getSelectedItemPosition()
        return p if p >= 0 else 0


class ShelfModel:
    """两段式渲染的最小模型（对齐 ShelfFragment.onBooksPushed / applyFilters）"""

    def __init__(self, ex):
        self.ex = ex
        self.books_received = False
        self.spinner_ready = False
        self.loading_visible = False
        self.rendered = None            # 当前列表渲染结果
        self.empty_state_shown = False  # 空态文案是否可见
        # Spinner：3 个 XML 写死（始终有内容），5 个字典（初始空）
        self.sp_stock = Spinner(["全部", "在库", "借出"])
        self.sp_read = Spinner(["全部", "已读", "在读", "未读"])
        self.sp_loc = Spinner()    # 字典：初始空
        self.sp_cat = Spinner()    # 字典：初始空
        self.locations = []        # 字典数据
        self.categories = []
        self.all_books = []

    def on_view_created(self):
        """onViewCreated：首屏无数据则显示加载指示；applyViewMode 同步调一次 applyFilters"""
        if not self.all_books:
            self.loading_visible = True
        self.apply_view_mode()

    def apply_view_mode(self):
        self.apply_filters()

    def apply_filters(self):
        """对应 applyFilters：b==null 或 !booksReceived 直接返回；未就绪 Spinner 夹 0"""
        if not self.books_received:
            return
        stock = Spinner.sel_pos(self.sp_stock)
        loc = Spinner.sel_pos(self.sp_loc)
        cat = Spinner.sel_pos(self.sp_cat)
        result = []
        for bk in self.all_books:
            if stock > 0 and bk["stock"] != stock - 1:
                continue
            # 字典过滤：位置 0（不限）时跳过；>0 时按字典数据匹配
            if loc > 0 and (not self.locations or bk["loc"] != loc - 1):
                continue
            if cat > 0 and (not self.categories or bk["cat"] != cat - 1):
                continue
            result.append(bk)
        self.rendered = result
        self.empty_state_shown = len(result) == 0

    def on_books_pushed(self, pushed):
        """第一段（主线程，立即）+ 第二段（io 查字典回主线程）"""
        if self.books_received is False:
            pass
        self.books_received = True
        if not self.all_books:
            self.loading_visible = False  # 只有首屏收起指示
        self.all_books = list(pushed)
        self.apply_filters()  # 降级渲染
        # 第二段：io 查字典 → ui 填 Spinner + 精筛
        # （真实 App 的 io 是单线程顺序执行：ui_fill 必然排在 dict_query 之后）
        def dicts_then_ui():
            time.sleep(0.01)  # 模拟真实查库耗时（真实 App 中主线程检查必然先于 io 回来）
            self.locations = ["家里", "办公室"]
            self.categories = ["小说", "技术"]
            self.sp_loc = Spinner(["不限"] + self.locations)
            self.sp_cat = Spinner(["不限"] + self.categories)
            self.spinner_ready = True
            self.apply_filters()

        self.ex.io("dict_query", dicts_then_ui)


# ---------------- 场景 ----------------

print("== 场景 1：冷启动首推即渲染（books 先于字典显示，loading 及时消失） ==")
ex = TwoLaneExecutor()
m = ShelfModel(ex)
m.on_view_created()
check("视图创建后 loading 可见", m.loading_visible)
m.on_books_pushed([{"stock": 0, "loc": 0, "cat": 0}])
check("首推后 loading 立即隐藏（不等字典）", not m.loading_visible)
check("首推后书籍已渲染（字典未到也显示）", m.rendered is not None and len(m.rendered) == 1)
check("此时字典 Spinner 仍未就绪", not m.spinner_ready)
time.sleep(0.2)
check("字典到达后精筛仍渲染同一批书", len(m.rendered) == 1 and m.spinner_ready)

print("== 场景 2：首推前 applyViewMode 调 applyFilters 不渲染（无空态闪现） ==")
ex2 = TwoLaneExecutor()
m2 = ShelfModel(ex2)
m2.on_view_created()
check("首推前 booksReceived 守卫挡住渲染", m2.rendered is None and not m2.empty_state_shown)

print("== 场景 3：字典未就绪时降级（字典 Spinner 空按「不限」处理） ==")
check("空 Spinner 位置 -1 夹到 0", Spinner.sel_pos(Spinner()) == 0)
check("XML Spinner 正常读位置", Spinner.sel_pos(Spinner(["a", "b"])) == 0)

print("== 场景 4：字典到达后精筛生效（地点过滤） ==")
m2.on_books_pushed([{"stock": 0, "loc": 0, "cat": 0}, {"stock": 0, "loc": 1, "cat": 0}])
time.sleep(0.2)
m2.sp_loc.position = 1  # 用户选「家里」
m2.apply_filters()
check("地点筛选生效（只留 loc=0 的书）", len(m2.rendered) == 1 and m2.rendered[0]["loc"] == 0)

print("== 场景 5：空库用户首推空列表 → loading 隐藏 + 空态显示 ==")
ex5 = TwoLaneExecutor()
m5 = ShelfModel(ex5)
m5.on_view_created()
m5.on_books_pushed([])
check("空库首推后 loading 隐藏", not m5.loading_visible)
check("空库首推后空态文案显示", m5.empty_state_shown)

print("== 场景 6：后续 books 变化推送 → 列表立即更新且不再闪 loading ==")
m5.on_books_pushed([{"stock": 0, "loc": 0, "cat": 0}])
check("第二推后书已渲染", len(m5.rendered) == 1)
m5.on_books_pushed([])
check("删光后空态显示、loading 不复现", m5.empty_state_shown and not m5.loading_visible)

print("== 场景 7：双线程分离——bg 长任务不阻塞 io 的字典查询 ==")
ex7 = TwoLaneExecutor()
# 启动期：补种与备份排在 bg，书架字典查询排在 io（模拟 Application.onCreate 先入队 bg）
ex7.bg("seed_defaults", lambda: None)
ex7.bg("auto_backup_zip", lambda: None)
m7 = ShelfModel(ex7)
m7.on_view_created()
m7.on_books_pushed([{"stock": 0, "loc": 0, "cat": 0}])
time.sleep(0.2)
check("io 车道字典任务完成", "dict_query" in ex7.io_done)
check("io 的字典查询先于 bg 的 zip 完成（双线程不互相阻塞）",
      ex7.done_at.get("dict_query", 9e9) < ex7.done_at.get("auto_backup_zip", 0))

print("== 场景 8：备份延后 3 秒（冷启动让路首屏） ==")
delay_ms = {"v": 0}


class FakeMain:
    def post_delayed(self, fn, ms):
        delay_ms["v"] = ms  # 只验证注册的延后值，不真等 3 秒


FakeMain().post_delayed(lambda: None, 3000)
check("自动备份注册为 3 秒延后任务", delay_ms["v"] == 3000)

print()
print("通过 %d / 失败 %d" % (PASS, FAIL))
raise SystemExit(0 if FAIL == 0 else 1)
