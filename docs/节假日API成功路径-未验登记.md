# 节假日 API 成功路径：未验登记（EP-I5 · T15）

时点封条：2026-11-13 · 分支 `ui/statistics` · 5.9.5 / versionCode 139 / DB v26
状态：**成功路径仍未验**。本文是规格步骤④要求的登记，**不是验收通过**。
本轮零主代码改动、零测试改动、零联网。

---

## 0. 规格四步，本轮做到哪一步

| 步骤 | 状态 |
| --- | --- |
| ① 先向用户索取样本或授权（遵守「大流量操作先确认」） | **已发出请求，等回话**，见 §5 |
| ② 拿到样本后存为测试资产，跑「解析 → 入库 → 30 天 TTL 刷新」全链断言 | **未执行**，被 §4 三条阻塞 |
| ③ 断言成功后 `fail_*` 退避键被清除 | **未执行**（断言已先写好，见 §6 第 2 条） |
| ④ 拿不到样本则登记「成功路径仍未验」+ 阻塞原因 + 解除条件 | **本文即是** |

---

## 1. 已经验了什么（别把「一段未验」说成「全未验」）

节假日相关 JVM 测试 **16 条**，每次 push 由 CI 跑：

| 文件 | 条数 | 覆盖 |
| --- | --- | --- |
| `HolidayJsonTest` | 5 | 解析：只收 `holiday=true`、忽略调休、多节日名取第一个并截到 3 字、周末不入表、非零 code / 坏 JSON / null / 空串一律返回空表、`ofApi` 与 `ofFallback` 的来源标记 |
| `HolidayCachePolicyTest` | 4 | 策略：非 Wi-Fi 不请求、TTL 边界（`TTL_MS - 1` 不刷 / `TTL_MS` 刷）、退避边界（`BACKOFF_MS - 1` 不重试 / `BACKOFF_MS` 重试）、首次无缓存可请求 |
| `HolidayCnTest` | 7 | 兜底节日名 |

**关键限定**：`HolidayJsonTest` 的输入是**手写夹具**（文件里的 `BODY` 常量，6 个条目，其中 `13-01` 是故意写坏的非法月），**不是 timor.tech 的真实响应**。所以「解析」这一段验的是「解析器符合我假设的 schema」，不是「解析器符合真实 schema」。这两件事差别很大，见 §3。

---

## 2. 没验的到底是哪一段（精确到行）

`HolidayStore.refreshIfNeeded` 的 **`onResponse` 成功分支**，`HolidayStore.java:118-139`：

| 行 | 干什么 | 验过没 |
| --- | --- | --- |
| `:120` | `response.isSuccessful()` 且 body 非空，否则抛 IOException | 否 |
| `:124` | `HolidayJson.parseYear(body, year)` 解析**真实** body | 否（只验过手写夹具） |
| `:125-127` | 空表当失败抛 IOException | 否 |
| `:128-132` | 写 `json_<year>` + `at_<year>`，并 **`remove("fail_" + year)`** | 否 ← **规格步骤③要断言的就是这一句** |
| `:133-134` | `HolidayCalendar.ofApi(map)` + `MEMORY.put` | 否 |
| `:135-139` | `MAIN.post` 把结果回调给 UI | 否 |

以及「30 天 TTL 刷新」的**闭环**：`HolidayCachePolicy.shouldRefresh` 作为纯函数已验（4 条），但「成功写入 `at_` → 下次 `refreshIfNeeded` 在 `:96` 读到这个 `at_` → 30 天内不再发请求」这条**串起来的链**从未跑过。纯函数对不代表接线对——这正是本轮 I5 的整体主题。

---

## 3. 为什么这不是小事：失败是**静默降级**

看失败分支怎么处理：`:140-141` 捕获异常后**只写一个 `fail_` 时间戳，不通知任何人**。而 `load()` 在 `:83` 走兜底（`HolidayCn.yearNames` + 农历推算）。

后果：如果 timor.tech 的真实 schema 与手写夹具不同——比如 key 不是 `"MM-DD"`、`holiday` 不是布尔、外层没有 `code` 字段、或者年度数据嵌在别的层级——`parseYear` 会**静默返回空表**，`:125` 判为失败，界面回落到兜底节日名。

**用户看到的界面完全正常**：日格照样有节日名、照样有节日色。区别只是名字来自农历推算而不是官方 API（调休上班日、官方节日名如「中华人民共和国国庆节」的截断口径都对不上）。所以「成功路径没验」的风险不是崩溃，是**一个永远看不出来的降级**——没有日志、没有提示、没有崩溃上报会告诉你它没接上。

---

## 4. 阻塞原因（三条，全部实测，不是推测）

### 4.1 设备只有蜂窝网，Wi-Fi 闸门过不去

2026-11-13 实测 `adb -s de5090fb shell dumpsys connectivity`：两个 `NetworkAgentInfo` 的 `Transports` **都是 CELLULAR**——`network{115}` 是 `MOBILE[NR]`（ims），`network{130}` 是 `MOBILE[LTE]`（3gnet，当前 active default network）。**没有任何 WIFI 传输**。

链路：`isWifi()`（`HolidayStore.java:155-161`）返回 false → `HolidayCachePolicy.shouldRefresh` 第一句 `if (!wifi) return false` → `refreshIfNeeded` 在 `:98-101` 直接 return，**请求根本不发**。

所以在 de5090fb 上无论怎么点打卡页、怎么切年视图，都到不了成功路径。这不是「多试几次」能解决的。

### 4.2 `HolidayStore` 没有注入缝隙

- `URL`（`:39`）、`CLIENT`（`:43`）、`PREFS`（`:38`）全是 `private static final`
- `refreshIfNeeded` 直接 `CLIENT.newCall(request).enqueue(...)`，没有可替换的 transport
- `isWifi` 是 `private static`，读真实 `ConnectivityManager`

要在**不联网**的前提下验成功分支，必须先给主代码加缝隙（把 transport 与 Wi-Fi 判定抽成可注入）。本轮步骤①要求先拿样本/授权，所以**没改**。

### 4.3 prefs 取证：这台设备上成功分支一次都没执行到

```
adb -s de5090fb shell run-as com.bai.bookshelf.devtest \
  cat /data/data/com.bai.bookshelf.devtest/shared_prefs/holiday_cache.xml
```

实测内容（全部，只有两个键）：

```xml
<map>
    <long name="fail_2026" value="1794368988425" />   <!-- 2026-11-11 11:49:48 -->
    <long name="fail_2027" value="1794197187931" />   <!-- 2026-11-09 12:06:27 -->
</map>
```

**没有任何 `json_*` 或 `at_*` 键** → `:128-132` 那段写入从未执行过。

反过来推一步（这是从代码结构推的，不是猜）：`fail_` 只有两处会写——`:113`（`onFailure`）和 `:141`（`onResponse` 里抛异常）。两处都在 `:98-101` 的 `shouldRefresh` 闸门**之后**，而闸门要求 Wi-Fi。所以 **2026-11-09 与 2026-11-11 那两个时刻，设备确实连着 Wi-Fi，请求确实发出去了，然后失败了**。

**但失败在哪一步，现有证据分不出来**：「网络到不了 timor.tech」（走 `:113`）与「响应拿到了但 `parseYear` 返回空表」（走 `:125` → `:141`）写的是**同一个键、同一个值语义**，而且这两处**都没有任何日志**（`HolidayStore` 全文没有 `Log.` 调用）。logcat 里也不会有痕迹。

这条正好把 §3 的结论钉实：**这个模块的失败是完全静默的，连事后取证都区分不了失败原因。** 想区分，要么拿到真实样本离线验解析（方案甲/乙），要么给失败分支补 ASCII 日志（属 I4 T12 那类改动，本轮不做）。

取证边界：正式包 `com.bai.bookshelf` **不可 run-as**（实测 `run-as: package not debuggable`），所以只能取 devtest 的 prefs。这不影响结论——两个包共用同一份 `HolidayStore` 代码。

---

## 5. 解除条件：拿到任一条就能往下走（请用户选）

| 方案 | 用户要做什么 | 我拿到后能做什么 | 联网/流量 | 代价 |
| --- | --- | --- | --- | --- |
| **甲（首选）** | 给一份**真实年度响应 JSON**：浏览器打开 `https://timor.tech/api/holiday/year/2026`，把整段 body 贴给我或存成文件 | 存为测试资产，立刻补 §6 的 3 条断言，**全程离线** | 用户侧一次，我侧零 | 最小 |
| **乙** | **授权我一次性联网取样本** | 我在本机 `curl` 该 URL 一次，把 body 存成资产，之后全部离线跑 | 我侧一次，约几十 KB | 小 |
| **丙** | 授权**加注入缝隙** + 给设备连 Wi-Fi | 改主代码抽 transport / isWifi，再在真机上跑通成功路径 | 设备侧一次年度请求 | 最大，要改主代码 + 要 Wi-Fi，本轮不推荐 |

甲乙都符合既有约定「优先本地/离线，减少移动数据与网络带宽；大流量操作先确认」。**在拿到甲或乙之前，本文状态保持「未验」，不许标为已验。**

---

## 6. 拿到样本后要补的 3 条断言（先写清楚，免得事后凑）

沿用 I3 约束「断言必须是具体数值，不许写『不为 null』」：

1. **解析真实 body**：`HolidayJson.parseYear(样本, 2026)` 的 size 等于样本里 `holiday=true` 且日期合法的条数（先手数一遍写死）；抽查 3 个具体日期的名称等于样本里的实际字符串经 `shortName` 后的值（例如 `20260101` → `"元旦"`）。
2. **入库 + 清退避键**：把样本喂进成功分支后，`holiday_cache.xml` 里 `json_2026` **等于样本原文**、`at_2026` **> 0**，且 **`fail_2026` 键不存在**（对应 `:131` 的 `remove`）。这条是规格步骤③的正面落地。
3. **TTL 闭环 + 来源标记**：以写入的 `at_` 为 `fetchedAt`，`shouldRefresh(at + TTL_MS - 1, at, 0, true) == false` 且 `shouldRefresh(at + TTL_MS, at, 0, true) == true`；并断言 `MEMORY` 里那份 calendar 的 `complete() == true`（证明来源是 API 而不是兜底，对应 §3 那个静默降级）。

第 2、3 条需要 4.2 的注入缝隙才能在离线环境跑；**甲/乙只够跑第 1 条 + 第 3 条的纯函数部分**。这个限制先写明，别到时候把「跑了 1 条」说成「全链已验」。

---

## 7. 本文不改什么

不改主代码、不改测试代码、不联网、不动 DB v26、不改布局与用户可见文案、不 commit 不 push。真机只做了只读取证（`dumpsys connectivity` + `run-as cat`），未改任何 prefs、未 `pm clear`。