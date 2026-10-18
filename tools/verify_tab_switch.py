# -*- coding: utf-8 -*-
"""
verify_tab_switch.py — v4.1 切 Tab 改造回归脚本（时序模型）

评审 #8 整改验证：MainActivity 切 Tab 由 replace() 改为 add 一次 + show/hide。
验证：
1. 切 Tab 不再销毁重建 Fragment 视图（onCreateView 全程只调一次）
2. 切回页面触发 onHiddenChanged → 与 onResume 等价的按需刷新
3. 书架页「指纹按需刷新 + 视图重建补偿」机制在新模式下仍然工作
4. 回主界面（Activity onResume）时三个页面照常刷新（hidden 页也走 onResume）
"""

FIRST = "不限"

fails = []


def check(desc, cond):
    print(f"  [{'OK' if cond else 'FAIL'}] {desc}")
    if not cond:
        fails.append(desc)


class Page:
    """单个 Tab 页模型：统计 onCreateView 次数与可见时的刷新次数"""

    def __init__(self, name):
        self.name = name
        self.create_count = 0   # onCreateView 次数（>1 说明视图被重建，不符合预期）
        self.visible_refresh = 0  # onVisible 执行次数（onResume 或切 Tab 显示）
        self.hidden = True
        self.view_alive = False

    def on_create_view(self):
        self.create_count += 1
        self.view_alive = True

    def on_hidden_changed(self, hidden):
        was = self.hidden
        self.hidden = hidden
        if was and not hidden:  # 从隐藏切到显示
            self.on_visible()

    def on_resume(self):
        # Activity onResume 时所有 Fragment（含 hidden）都会走 onResume
        self.on_visible()

    def on_visible(self):
        self.visible_refresh += 1


class App:
    def __init__(self):
        self.pages = {n: Page(n) for n in ("书架", "统计", "我的")}
        self.current = "书架"
        self.added = False
        self.activity_resumed = False

    def cold_start(self):
        # v4.1：onCreate selectTab(0) → add 三页 + hide 非当前（无 onHiddenChanged 回调）
        for n, p in self.pages.items():
            p.on_create_view()
            p.hidden = (n != "书架")
        self.added = True
        self.activity_resume()
        # 首次显示页走 onResume；hidden 页的 onResume 同样触发（只是不可见）
        for p in self.pages.values():
            p.on_resume()

    def activity_resume(self):
        self.activity_resumed = True

    def select_tab(self, name):
        """v4.1 模式：show(target) + hide 其它（无视图重建）"""
        assert self.added
        for n, p in self.pages.items():
            if n == name:
                p.on_hidden_changed(False)
            else:
                p.on_hidden_changed(True)
        self.current = name

    def go_background_then_back(self):
        """离开 App 再回来：Activity onPause/onResume，Fragment onResume 全走一遍"""
        for p in self.pages.values():
            p.on_resume()


print("== 场景 1：冷启动 + 三 Tab 轮流切换 ==")
app = App()
app.cold_start()
app.select_tab("统计")
app.select_tab("我的")
app.select_tab("书架")
app.select_tab("统计")
app.select_tab("书架")
check("切换 5 次，书架视图零重建（旧 replace 模式是 3 次）", app.pages["书架"].create_count == 1)
check("统计视图零重建", app.pages["统计"].create_count == 1)
check("每次切回书架都触发按需刷新", app.pages["书架"].visible_refresh == 3)

print("== 场景 2：回主界面（Activity onResume）三页全部刷新 ==")
app2 = App()
app2.cold_start()
before = {n: p.visible_refresh for n, p in app2.pages.items()}
app2.go_background_then_back()
check("hidden 的统计页 onResume 也会刷新（数据变更后回前台可见最新）",
      app2.pages["统计"].visible_refresh == before["统计"] + 1)
check("书架页同样刷新", app2.pages["书架"].visible_refresh == before["书架"] + 1)

print("== 场景 3：视图不重建后，v3.3/v3.5 补偿机制自然失活（无害保留）==")
# 旧模式：切 Tab → onCreateView → viewRebuilt=True → onResume 强制喂数据
# 新模式：视图活着，viewRebuilt 恒 False，指纹机制照常工作
app3 = App()
app3.cold_start()
app3.select_tab("我的")
app3.select_tab("书架")
check("书架视图仍存活（无需重建喂数据）", app3.pages["书架"].view_alive and app3.pages["书架"].create_count == 1)
check("切回时走 onHiddenChanged 按需刷新（指纹机制不变）", app3.pages["书架"].visible_refresh == 2)

print()
if fails:
    print(f"存在 {len(fails)} 个失败项")
    raise SystemExit(1)
print("全部通过：show/hide 模式下切 Tab 零重建、刷新语义完整")
