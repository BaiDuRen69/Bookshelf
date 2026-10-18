# -*- coding: utf-8 -*-
"""
v4.5 主界面 Tab 重复挂载修复回归脚本（Python 最小模型）

模拟 bug：Activity 重建（进程被杀后恢复/旋转屏）时 FragmentManager 自动还原
上次的三个 Fragment 实例；v4.1 的首次挂载逻辑不查这一点，直接把新 new 的
实例再 add 一遍 → 新旧两套叠加，旧书架 hide 不掉、透在统计/我的下面。

对照旧逻辑（复现 bug）与新逻辑（MainActivity v4.5 修复）：
1. 冷启动：各 Tab 只挂载一份
2. 重建恢复：复用还原实例，FM 里始终只有 3 个 Fragment
3. 重建后切到「我的」：旧书架被隐藏，不透底
4. 重建后切回书架：书架正常显示
"""
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


class Frag:
    def __init__(self, name):
        self.name = name
        self.visible = True


class FakeFM:
    """最小 FragmentManager：add 挂载（带 tag），hide/show 切可见性，还原场景预置旧实例"""

    def __init__(self, restored=None):
        self.items = []          # (tag, Frag)
        self.restored = restored or {}  # tag -> Frag（重建时 FM 自动还原的实例）

    def findFragmentByTag(self, tag):
        for t, f in self.items:
            if t == tag:
                return f
        return self.restored.get(tag)

    def add(self, container, f, tag):
        self.items.append((tag, f))

    def show(self, f):
        f.visible = True

    def hide(self, f):
        f.visible = False

    def allFragments(self):
        # 还原实例 + 本次 add 的实例（重建 bug 时两者并存）
        seen, out = set(), []
        for t, f in self.items + list(self.restored.items()):
            if f not in seen:
                seen.add(f)
                out.append(f)
        return out


class MainModel:
    """对齐 MainActivity v4.5 的 selectTab 首挂逻辑（use_restore 开关对照旧逻辑）"""

    def __init__(self, use_restore, restored=None):
        self.fm = FakeFM(restored)
        self.use_restore = use_restore
        self.fragments_added = False

    def on_create(self, has_saved_state):
        # 旧逻辑：字段初始化无条件 new；新逻辑：savedInstanceState 有还原实例则复用
        restored = self.fm.restored
        if self.use_restore and has_saved_state and "tab_shelf" in restored:
            self.shelf = restored["tab_shelf"]
            self.stats = restored["tab_stats"]
            self.mine = restored["tab_mine"]
        else:
            self.shelf = Frag("Shelf@new")
            self.stats = Frag("Stats@new")
            self.mine = Frag("Mine@new")
        self.select_tab(0, has_saved_state)

    def select_tab(self, index, has_saved_state=False):
        target = {0: self.shelf, 1: self.stats, 2: self.mine}[index]
        if not self.fragments_added:
            # v4.5 新逻辑：FM 里已有同名 tag 实例则不再 add；旧逻辑无条件 add
            for f, tag in ((self.shelf, "tab_shelf"), (self.stats, "tab_stats"), (self.mine, "tab_mine")):
                already = self.use_restore and self.fm.findFragmentByTag(tag) not in (None, f)
                if not already:
                    self.fm.add("container", f, tag)
                if f == target:
                    self.fm.show(f)
                else:
                    self.fm.hide(f)
            self.fragments_added = True
        else:
            for f in (self.shelf, self.stats, self.mine):
                if f == target:
                    self.fm.show(f)
                else:
                    self.fm.hide(f)


print("== 场景 1：冷启动（无还原）——新逻辑各 Tab 只挂载一份 ==")
m = MainModel(use_restore=True)
m.on_create(has_saved_state=False)
check("FM 里共 3 个 Fragment", len(m.fm.allFragments()) == 3)
check("书架可见、另两页隐藏", m.shelf.visible and not m.stats.visible and not m.mine.visible)

print("== 场景 2：进程被杀后恢复——复用还原实例，不重复 add ==")
old_shelf = Frag("Shelf@restored(old)")
old_stats = Frag("Stats@restored(old)")
old_mine = Frag("Mine@restored(old)")
m2 = MainModel(use_restore=True, restored={"tab_shelf": old_shelf, "tab_stats": old_stats, "tab_mine": old_mine})
m2.on_create(has_saved_state=True)
check("FM 里仍然只有 3 个 Fragment（无新旧叠加）", len(m2.fm.allFragments()) == 3)
check("字段指向的是还原实例", m2.shelf is old_shelf and m2.mine is old_mine)
check("书架强制显示为当前页", m2.shelf.visible and not m2.stats.visible and not m2.mine.visible)

print("== 场景 3：恢复后切到「我的」——旧书架被隐藏，不透底 ==")
m2.select_tab(2)
check("我的可见", m2.mine.visible)
check("书架（还原实例）已隐藏——bug 场景修复", not m2.shelf.visible and not old_shelf.visible)
check("统计也已隐藏", not m2.stats.visible)

print("== 场景 4：恢复后切回书架——正常显示 ==")
m2.select_tab(0)
check("书架可见", m2.shelf.visible)
check("我的隐藏", not m2.mine.visible)

print("== 场景 5：旧逻辑对照——复现 v4.4 的透底 bug ==")
m5 = MainModel(use_restore=False, restored={"tab_shelf": Frag("Shelf@restored(old)"),
                                            "tab_stats": Frag("Stats@restored(old)"),
                                            "tab_mine": Frag("Mine@restored(old)")})
m5.on_create(has_saved_state=True)
m5.select_tab(2)
check("旧逻辑 FM 里叠加成 6 个 Fragment", len(m5.fm.allFragments()) == 6)
check("旧逻辑还原的书架仍可见（即用户截图的透底）", m5.fm.restored["tab_shelf"].visible)

print()
print("通过 %d / 失败 %d" % (PASS, FAIL))
raise SystemExit(0 if FAIL == 0 else 1)
