"""
书架页 Spinner 填充逻辑的回归验证（v3.3 bug 复现与修复验证）。

背景：v3.2 引入「内容没变就不重建 Spinner 适配器」的优化后，
用户反馈「书架页面的分类不能显示和选择了」——截图里只有布局里写死
android:entries 的下拉（状态/阅读/排序）正常，需要代码填充的
分类/标签/地点/来源/国家全是空白。

根因：Spinner 是每次 onCreateView 全新创建的，而「内容缓存」是
Fragment 实例级字段、跨视图重建存活。视图重建后：
  - 新 Spinner 的 adapter == null（空白）
  - 但 cache 里存着上次的内容
  - 于是「内容没变」判断成立 → 跳过填充 → 永久空白

本脚本用最小模型复现这个状态机，验证修复后的逻辑。

运行：python tools/verify_shelf_spinner.py
"""
import sys


class Spinner:
    """模拟 Android Spinner 的最小行为"""

    def __init__(self):
        self.adapter = None      # None 表示空白未填充
        self.items = []
        self.selection = -1

    def set_adapter(self, items):
        self.adapter = list(items)
        self.items = list(items)
        # 真实 Spinner 在 setAdapter 后会回到第 0 项
        self.selection = 0 if items else -1

    def set_selection(self, index, animate=False):
        if 0 <= index < len(self.items):
            self.selection = index

    def get_selected_text(self):
        if 0 <= self.selection < len(self.items):
            return self.items[self.selection]
        return None


class ShelfSpinnerLogic:
    """复刻 ShelfFragment 的 Spinner 填充逻辑"""

    def __init__(self):
        # Fragment 实例级字段：跨视图重建存活
        self.cache = []
        self.spinnersFilled = False
        self.spinnerReady = False
        self.selection_memory = {}   # v3.3: 以 cache 为 key 记住用户选中的文案

    def on_create_view(self, spinner):
        """v3.3 修复：先保存选中态，再复位所有状态"""
        # 视图销毁前，记住当前选中的非首项；选首项则清除记忆
        cur = spinner.get_selected_text()
        first = self.cache[0] if self.cache else None
        if cur and cur != first and self.cache:
            self.selection_memory[id(self.cache)] = cur
        else:
            self.selection_memory.pop(id(self.cache), None)
        spinner.adapter = None       # 新 Spinner 是空的
        spinner.items = []
        spinner.selection = -1
        self.spinnersFilled = False
        self.spinnerReady = False
        self.cache.clear()

    def fill(self, spinner, names):
        """fillSpinnerIfChanged 的等价实现"""
        if spinner.adapter is None:
            self.cache.clear()
            self.cache.extend(names)
            spinner.set_adapter(names)
            # v3.3：恢复此前记住的选中项
            remembered = self.selection_memory.get(id(self.cache))
            if remembered in names:
                spinner.set_selection(names.index(remembered))
            return "filled(empty spinner)"
        if self.spinnersFilled and names == self.cache:
            return "skipped(unchanged)"
        selected_text = spinner.get_selected_text()
        selected_index = names.index(selected_text) if selected_text in names else -1
        self.cache.clear()
        self.cache.extend(names)
        spinner.set_adapter(names)
        if selected_index > 0:
            spinner.set_selection(selected_index)
        return "rebuilt"

    def on_item_selected(self, spinner, position):
        """模拟用户选择某一项"""
        spinner.set_selection(position)
        cur = spinner.get_selected_text()
        first = self.cache[0] if self.cache else None
        if position <= 0 or not cur or cur == first:
            # 用户主动选回「不限」→ 清除记忆
            self.selection_memory.pop(id(self.cache), None)
        else:
            self.selection_memory[id(self.cache)] = cur

    def on_data_loaded(self, spinner, names):
        """loadData 完成后调用"""
        result = self.fill(spinner, names)
        self.spinnersFilled = True
        self.spinnerReady = True
        return result


def check(label, cond, detail=""):
    if cond:
        print("  [OK] %s %s" % (label, detail))
    else:
        print("  [FAIL] %s %s" % (label, detail))
        return False
    return True


def test_bug_scenario():
    """复现用户报告的 bug：切 Tab 回来后分类下拉空白"""
    print("== 场景 1：首次加载 → 切 Tab 走 → 回来（用户报告的 bug）==")
    logic = ShelfSpinnerLogic()
    spinner = Spinner()

    # 首次进入：视图创建
    logic.on_create_view(spinner)
    print("  视图1 创建，填充数据 ..", end=" ")
    print(logic.on_data_loaded(spinner, ["分类", "小说", "计算机"]))
    ok = check("首次加载有内容", spinner.items == ["分类", "小说", "计算机"],
               str(spinner.items))

    # 切到统计页再切回书架 → Fragment 视图重建
    print("  切 Tab 走再回来 → 视图重建 ..", end=" ")
    logic.on_create_view(spinner)
    print("spinner.adapter =", spinner.adapter, "(空)")

    # 数据再次加载（注意：内容其实没变）
    print("  数据加载（内容未变） ..", end=" ")
    r = logic.on_data_loaded(spinner, ["分类", "小说", "计算机"])
    print(r)

    ok &= check("视图重建后必须重新填充", spinner.items == ["分类", "小说", "计算机"],
                str(spinner.items))
    ok &= check("可以显示与选择", len(spinner.items) > 1 and spinner.selection >= 0,
                "选中项=%s" % spinner.get_selected_text())
    return ok


def test_select_preserved():
    """视图重建后，用户之前的筛选选中态应被保留"""
    print()
    print("== 场景 2：用户选了「小说」，视图重建后应保持选中 ==")
    logic = ShelfSpinnerLogic()
    spinner = Spinner()
    logic.on_create_view(spinner)
    logic.on_data_loaded(spinner, ["分类", "小说", "计算机"])
    logic.on_item_selected(spinner, 1)  # 用户选中「小说」
    print("  用户选中 ->", spinner.get_selected_text())

    logic.on_create_view(spinner)  # 视图重建
    logic.on_data_loaded(spinner, ["分类", "小说", "计算机", "历史"])
    print("  重建并填充后 ->", spinner.get_selected_text())
    ok = check("选中态被保留", spinner.get_selected_text() == "小说",
               str(spinner.get_selected_text()))

    # 再验证：用户主动改回「不限」后，不应再强制恢复旧值
    logic.on_item_selected(spinner, 0)
    logic.on_create_view(spinner)
    logic.on_data_loaded(spinner, ["分类", "小说", "计算机", "历史"])
    ok &= check("改回不限后不再强制恢复", spinner.get_selected_text() == "分类",
                str(spinner.get_selected_text()))
    return ok


def test_no_waste():
    """同一视图内内容真没变时，仍应跳过重建（v3.2 的优化不能丢）"""
    print()
    print("== 场景 3：同一视图内重复刷新，内容未变（优化仍生效）==")
    logic = ShelfSpinnerLogic()
    spinner = Spinner()
    logic.on_create_view(spinner)
    logic.on_data_loaded(spinner, ["分类", "小说"])
    r1 = logic.on_data_loaded(spinner, ["分类", "小说"])
    r2 = logic.on_data_loaded(spinner, ["分类", "小说"])
    print("  第二次:", r1, "| 第三次:", r2)
    ok = check("未变化时跳过重建", r1 == "skipped(unchanged)" and r2 == "skipped(unchanged)")
    ok &= check("内容仍然正确", spinner.items == ["分类", "小说"], str(spinner.items))
    return ok


def test_content_changed():
    """内容真变了必须重建"""
    print()
    print("== 场景 4：新增了一本书，分类库多了一项 ==")
    logic = ShelfSpinnerLogic()
    spinner = Spinner()
    logic.on_create_view(spinner)
    logic.on_data_loaded(spinner, ["分类", "小说"])
    r = logic.on_data_loaded(spinner, ["分类", "小说", "历史"])
    print("  结果:", r)
    ok = check("内容变化时重建", r == "rebuilt")
    ok &= check("新项已出现", "历史" in spinner.items, str(spinner.items))
    return ok


def test_empty_dict():
    """字典库为空时也要有首项，不能是空白 Spinner"""
    print()
    print("== 场景 5：字典库为空（全新用户）==")
    logic = ShelfSpinnerLogic()
    spinner = Spinner()
    logic.on_create_view(spinner)
    logic.on_data_loaded(spinner, ["分类"])
    ok = check("至少有首项可显示", spinner.items == ["分类"], str(spinner.items))
    logic.on_create_view(spinner)
    logic.on_data_loaded(spinner, ["分类"])
    ok &= check("重建后仍有首项", spinner.items == ["分类"], str(spinner.items))
    return ok


def main():
    results = [
        test_bug_scenario(),
        test_select_preserved(),
        test_no_waste(),
        test_content_changed(),
        test_empty_dict(),
    ]
    print()
    if all(results):
        print("全部通过：v3.3 修复有效，且 v3.2 的性能优化未被破坏")
    else:
        print("存在失败项，需继续修复")
        sys.exit(1)


if __name__ == "__main__":
    main()
