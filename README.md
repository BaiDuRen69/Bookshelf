<div align="center">

# 📚 小书房

**管理你家里的每一本纸质书**

扫码 / OCR 录入 · 智能书架 · 借阅提醒 · 阅读记录 · 藏书统计

[![最新版本](https://img.shields.io/github/v/release/BaiDuRen69/Bookshelf?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&color=2e7d32)](https://github.com/BaiDuRen69/Bookshelf/releases/latest)
[![系统要求](https://img.shields.io/badge/%E7%B3%BB%E7%BB%9F%E8%A6%81%E6%B1%82-Android%207.0%20%E5%8F%8A%E4%BB%A5%E4%B8%8A-blue)](https://github.com/BaiDuRen69/Bookshelf/releases/latest)
[![问题反馈](https://img.shields.io/badge/%E5%8F%8D%E9%A6%88%E9%97%AE%E9%A2%98-Issues-orange)](https://github.com/BaiDuRen69/Bookshelf/issues/new)

</div>

---

> 💡 **小书房是什么？**
> 一款免费的手机应用，帮你把家里的纸质书记进手机：扫一下书背后的条形码，书名、作者、出版社、封面就会自动填好。以后想找某本书放在哪、借给谁了，打开小书房一查就知道。

## ✨ 它能做什么

- 📷 **录入**：扫码 ISBN、连续扫描、批量队列、拍照识字（封面 / 扉页 OCR），书名作者出版社自动补全
- 🗄️ **书架浏览**：像逛书架一样翻看藏书，列表 / 封面网格 / 纯文字三种视图，搜索、筛选、11 种排序
- 🏷️ **整理**：分类（两级）/ 标签 / 存放地点 / 系列，智能书架（在读、借出中、超期、待完善…），支持日期与价格区间筛选
- 📍 **记录位置**：这本书放在书房还是客厅？记下来，一搜就能找到，不用到处翻
- 🤝 **借阅管理**：应还日期、到期通知、借阅人联系方式、一键复制催还文案、归还确认
- 📖 **阅读记录**：开始 / 读完日期、阅读进度、年度目标；摘抄、批注、书评按时间线保存
- 📚 **系列 / 套装**：给多卷本记上系列名和卷号，自动按阅读顺序收好、提示缺哪一卷，套装价还能一键分摊写回
- 🛒 **想读清单**：买之前先记下书名、作者、优先级、心理价，支持连续添加与自动查重，买到后一键转为藏书
- 🧹 **盘点模式**：扫一遍书房，结束给出缺失 / 多出清单，可一键标记遗失
- 📤 **分享**：单本书生成藏书票图片，多选生成文字书单
- 📊 **藏书统计**：一共多少本书、最贵的书是哪本、最喜欢哪个作者；购书统计按月 / 年 / 总看购入、录入与花费
- 🔒 **应用锁**：可选开启，用手机的指纹 / 面容或锁屏密码保护书架，离开 1 分钟后自动锁定
- 🌈 **多套主题**：7 套配色（含夜读深色），每套有独立纸色
- ☁️ **备份恢复**：zip 全量备份（含图片 / 借阅 / 摘抄）与 Excel 清单导出

## 🆕 版本与更新

**每个版本改了什么，只在 [Release.md](Release.md) 里维护**：一个版本一节，从最早的排到最新的。

- 下载地址：[Releases 页](https://github.com/BaiDuRen69/Bookshelf/releases/latest)，取版本号最大的那个 `XiaoShuFang-v*.apk`
- 交付包的真实数字（体积 / SHA-256 / 产出时的提交 / 能不能装）记在 [docs/发版记录.md](docs/发版记录.md)
- 功能按模块的清单在 [docs/小书房-功能清单.md](docs/小书房-功能清单.md)，开发记录与踩过的坑在 [docs/小书房-项目总览.md](docs/小书房-项目总览.md)，改进方案（13 条编号需求 + 逐条核实与执行结果）在 [docs/改进方案-2026-11-10.md](docs/改进方案-2026-11-10.md)
- 还没做的功能只认一份台账：[docs/功能欠账.md](docs/功能欠账.md)，三态（未做 / 已做 / 已作废）各带可复跑的核实命令；包体瘦身与内嵌字体的三案代价、字体文件规格在 [docs/包体与字体决策单.md](docs/包体与字体决策单.md)，两项都待裁决
- 这一页以前从 v4.20 一路逐版写到 v5.9.0（约 330 行 changelog），现在不再在这里重复维护；需要旧文案用 `git log -p --follow README.md` 取回

## 📲 下载安装

1. 打开 [**Releases 下载页**](https://github.com/BaiDuRen69/Bookshelf/releases/latest)，下载版本号最大的 `XiaoShuFang-v*.apk`
2. 在手机上点开下载好的文件（若提示「不允许安装未知应用」，按手机提示允许即可）
3. 安装完成，打开小书房，开始扫第一本书 🎉

> 📱 需要 Android 7.0 或以上版本；应用完全免费，无广告，不需要注册账号。安装包为 ARM 架构（arm64-v8a / armeabi-v7a），x86 平板或模拟器请使用 debug 构建。

## 🚀 三步开始使用

**第一步：扫码添加书** —— 打开 App 点「扫码」，对准书背后的条形码，「嘀」一声书就进书架了。

**第二步：告诉它书在哪** —— 添加时选一下存放位置（书房、客厅……），以后一搜就能找到。

**第三步：开个自动备份** —— 在「我的 → 自动备份」里打开开关，藏书数据会定期自动备份，安心省心。

## 💾 你的数据很安全

- 所有藏书数据**只保存在你自己的手机里**，不上传任何服务器，不需要注册登录
- 可在「我的 → 设置 → 应用锁」开启指纹 / 面容或锁屏密码，离开 1 分钟后自动锁定
- **zip 自动备份**是真正的全量备份（含图片、借阅记录、摘抄、设置项），建议开启
- **Excel 导出**适合查看和分享书单，但它不含借阅记录等信息，不能替代 zip 备份
- 换新手机时：旧手机导出 zip 备份 → 新手机「导入备份」→ 藏书完整迁移
- 数据库每次升级前自动留一份原始库副本，升级全部是保数据迁移，覆盖安装不丢书

## ❓ 常见问题

<details>
<summary><b>扫码后搜不到这本书？</b></summary>
<br>

少数书籍（尤其是个别小众出版物）的数据库里没有收录。你可以在录入页手动填写书名等信息，一样能收藏。也可以在「我的 → API 设置」中配置图书查询数据源来提高识别率（见下方「技术信息」），不配置不影响使用。
</details>

<details>
<summary><b>换手机了，藏书怎么迁移？</b></summary>
<br>

旧手机：我的 → 备份文件管理 → 选一份备份 → 分享到应用外（微信文件传输助手 / 网盘均可）。
新手机：安装小书房 → 我的 → 导入备份 → 选择 zip 文件 → 完成。
</details>

<details>
<summary><b>误删了书还能找回吗？</b></summary>
<br>

删除后 8 秒内的撤销条能原样复原（连逐本信息、摘抄、图片一起回来）；过了这段时间用最近一次 zip 备份恢复（「我的 → 导入备份」）。恢复会覆盖当前数据，所以开启自动备份很有必要。
</details>

<details>
<summary><b>借出去的书老忘，有提醒吗？</b></summary>
<br>

有。到期和逾期都会提醒，统计图标上的红点就是逾期未还的本数，点一下直达在借清单；「我的 → 设置 → 通知与提醒」可以逐项开关，还能一键生成催还文案发给对方。书在别人手上的那段时间不计入你的阅读时长。
</details>

## 💬 有问题或建议？

非常欢迎！这是让小书房变得更好用的最快方式：

- 🐛 [**点这里提交问题**](https://github.com/BaiDuRen69/Bookshelf/issues/new)（需要 GitHub 账号，注册免费）
- 📧 或者发邮件到：**baiduren69@outlook.com**
- 📱 手机上最方便：打开小书房 →「我的」页 → 选择「邮件反馈」或「GitHub Issues 反馈」

---

## 🛠️ 技术信息（开发者 / 进阶用户）

<details>
<summary>点击展开：技术栈、数字口径、构建方式与数据源配置</summary>

### 技术栈

原生 Android（Java）· Room（当前 DB v26，升级全部为保数据迁移）· CameraX 1.4.2 + ML Kit 17.3.0（条形码识别）+ ML Kit 文本识别 16.0.1（封面 OCR，
原生库均已适配 Android 15+ 的 16 KB 内存页）·
Retrofit · Glide · 零依赖手写 XLSX 读写器（不引入 POI）· EncryptedSharedPreferences（API 密钥加密存储）· androidx.biometric（应用锁）

最新版本 v5.9.7（versionCode 141，单测 644，androidTest 共 187）。

### 数字的口径

- 版本号与测试规模的真值只在代码里：`app/build.gradle` 的 versionName / versionCode、`AppDatabase.VERSION`、`app/src/test` 与 `app/src/androidTest` 的 `@Test` 计数
- `tools/check_doc_drift.py` 拿这些真值逐一比对本页与 `docs/` 里的当前态说法，对不上就红，所以这里不手写凭印象的数字
- `tools/check_activities.py` 守住「每个 Activity 必须有可达的 setContentView」（v4.49 整页空白事故的自动版）
- `scripts/pack.ps1` 与 `tools/check_release_package.py` 核验包名 / ABI / 文件名版本，并与上一条可交付记录比 SHA-256 与体积；交付目录默认是仓库外的 `../dist/`（历史包 `dist/历史/`、改前改后对拍证据 `dist/verify/<版本>/`）
- 硬编码中文只有一个数：`tools/count_cn_literals.py` 的「含中文」= **2657**（口径 = 只扫 `app/src/main/java`、逐行取 `//` 之前、抓字符串字面量、串内 ≥1 个汉字算一条）。CI 用 `--max 2657` 做只减不增护栏。其它脚本报的是别的口径，只能当差值解释、不能拿来「更正」它：`tools/audit_cn_and_catch.py` 的 2227 = 再收严到 ≥2 汉字（−368 条单字量词「册/本/笔」）并去掉块注释（−62 条）；lint 基线里的 668 `HardcodedText` + 104 `SetTextI18n` 数的是 **XML 布局**条目，跟 Java 字面量不是一个东西
- CI：`.github/workflows/ci.yml`（assembleRelease + 单测 + lint + 上述四条校验 + 中文只减不增护栏 + 负向自测）；纯文档改动不触发构建

### 构建

Android Studio 打开本目录直接同步即可；或命令行：

```
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

要求：JDK 21 工具链（语言级别 Java 17）、Android SDK Platform 36、Gradle 9.4.1（wrapper 已指定）。
本机工程路径含中文时，Gradle 9 的测试 worker 可能无法从中文目录加载测试类；`app/build.gradle`
末尾已内置 ASCII 临时 jar 的 workaround，换到纯英文路径可删除。

交付物为 **release 构建**（v4.23 起）：同 debug 签名、功能一致，但
`debuggable=false`（adb 无法 run-as 读应用数据，EncryptedSharedPreferences 的密钥保护真正生效），
并强制 https（debug 构建仍放行明文便于抓包调试）。debug 构建包名带 `.devtest` 后缀，不用于交付。
Room schema 导出于 `app/schemas/`（迁移比对与测试用）。

### ISBN 数据源配置

ISBN 数据源在「我的 → API 设置」中填写，支持三种鉴权方式（默认腾讯云市场签名）。
密钥使用 AndroidKeyStore 加密存储，不会明文落盘。
这个 API 可以到腾讯云或阿里云搜索 ISBN，然后找到 ISBN 图书查询类似的服务购买即可，部分云服务是免费的但是数据不全或错误，可自行探索。
已适配腾讯云市场「聚美智数 ISBN 书号查询」（产品 ID 34136）（我目前使用的就是这个 https://market.cloud.tencent.com/products/34136 ）：

1. 鉴权方式选「腾讯云市场签名（SecretId+SecretKey）」
2. 请求地址填 API 接口调用地址，例如 `https://ap-guangzhou.cloudmarket-apigw.com/service-ed62t9xo/isbn/query`
3. SecretId / SecretKey 在腾讯云控制台 → 云市场（我的资源）→ 已购产品与服务 → 管理 → 应用信息
4. 未配置时不影响使用：扫码后直接进入手动录入

</details>

## 📄 使用条款与免责声明

- 仅供个人学习与非商业用途。
- 数据仅保存在本机，卸载或清除数据将导致藏书记录丢失，请定期使用 zip 自动备份并妥善保存备份文件。
- 图书信息与豆瓣评分来自第三方公开接口/网页，其准确性与可用性由提供方负责；豆瓣非官方接口，可能随其改版失效。
- 请遵守数据源服务商的服务条款，勿将接口用于商业用途。

## ©️ 版权

Copyright (c) 2026 bai. All rights reserved.
