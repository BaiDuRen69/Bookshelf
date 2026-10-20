<div align="center">

# 📚 小书房

**管理你家里的每一本纸质书**

扫码录入 · 书架浏览 · 借阅记录 · 藏书统计

[![最新版本](https://img.shields.io/github/v/release/BaiDuRen69/Bookshelf?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&color=2e7d32)](https://github.com/BaiDuRen69/Bookshelf/releases/latest)
[![系统要求](https://img.shields.io/badge/%E7%B3%BB%E7%BB%9F%E8%A6%81%E6%B1%82-Android%207.0%20%E5%8F%8A%E4%BB%A5%E4%B8%8A-blue)](https://github.com/BaiDuRen69/Bookshelf/releases/latest)
[![问题反馈](https://img.shields.io/badge/%E5%8F%8D%E9%A6%88%E9%97%AE%E9%A2%98-Issues-orange)](https://github.com/BaiDuRen69/Bookshelf/issues/new)

</div>

---

> 💡 **小书房是什么？**
> 一款免费的手机应用，帮你把家里的纸质书记进手机：扫一下书背后的条形码，书名、作者、出版社、封面就会自动填好。以后想找某本书放在哪、借给谁了，打开小书房一查就知道。

## ✨ 它能做什么

- 📷 **扫码录入**：对准书背后的 ISBN 条形码扫一下，书籍信息自动补全，支持连续扫描
- 🗄️ **书架浏览**：像逛书架一样翻看藏书，列表 / 封面网格 / 纯文字三种视图，搜索、筛选、11 种排序
- 📍 **记录位置**：这本书放在书房还是客厅？记下来，一搜就能找到，不用到处翻
- 🤝 **借阅管理**：书借给了谁、什么时候借的，一目了然，不怕有去无回
- 📊 **藏书统计**：一共多少本书、最贵的书是哪本、最喜欢哪个作者，都有答案
- 🌈 **多套主题**：6 种配色随心切换
- ☁️ **备份恢复**：一键备份全部数据，换手机也能把藏书完整带走

## 📲 下载安装

1. 打开 [**Releases 下载页**](https://github.com/BaiDuRen69/Bookshelf/releases/latest)，下载 `XiaoShuFang-v4.7.apk`
2. 在手机上点开下载好的文件（若提示「不允许安装未知应用」，按手机提示允许即可）
3. 安装完成，打开小书房，开始扫第一本书 🎉

> 📱 需要 Android 7.0 或以上版本；应用完全免费，无广告，不需要注册账号。

## 🚀 三步开始使用

**第一步：扫码添加书** —— 打开 App 点「扫码」，对准书背后的条形码，「嘀」一声书就进书架了。

**第二步：告诉它书在哪** —— 添加时选一下存放位置（书房、客厅……），以后一搜就能找到。

**第三步：开个自动备份** —— 在「我的 → 自动备份」里打开开关，藏书数据会定期自动备份，安心省心。

## 💾 你的数据很安全

- 所有藏书数据**只保存在你自己的手机里**，不上传任何服务器，不需要注册登录
- **zip 自动备份**是真正的全量备份（含图片、借阅记录），建议开启
- **Excel 导出**适合查看和分享书单，但它不含借阅记录等信息，不能替代 zip 备份
- 换新手机时：旧手机导出 zip 备份 → 新手机「导入备份」→ 藏书完整迁移

## ❓ 常见问题

<details>
<summary><b>扫码后搜不到这本书？</b></summary>
<br>

少数书籍（尤其是个别小众出版物）的数据库里没有收录。你可以在录入页手动填写书名等信息，一样能收藏。也可以在「我的 → API 设置」中配置图书查询数据源来提高识别率（见下方「技术信息」），不配置不影响使用。
</details>

<details>
<summary><b>换手机了，藏书怎么迁移？</b></summary>
<br>

旧手机：我的 → 找到自动备份的 zip 文件，发送给自己（微信文件传输助手 / 网盘均可）。
新手机：安装小书房 → 我的 → 导入备份 → 选择 zip 文件 → 完成。
</details>

<details>
<summary><b>误删了书还能找回吗？</b></summary>
<br>

用最近一次的 zip 备份恢复即可找回（「我的 → 导入备份」）。注意恢复会覆盖当前数据，所以开启自动备份很有必要。
</details>

## 💬 有问题或建议？

非常欢迎！这是让小书房变得更好用的最快方式：

- 🐛 [**点这里提交问题**](https://github.com/BaiDuRen69/Bookshelf/issues/new)（需要 GitHub 账号，注册免费）
- 📧 或者发邮件到：**baiduren69@outlook.com**
- 📱 手机上最方便：打开小书房 →「我的」页 → 选择「邮件反馈」或「GitHub Issues 反馈」

---

## 🛠️ 技术信息（开发者 / 进阶用户）

<details>
<summary>点击展开：技术栈、构建方式与数据源配置</summary>

### 技术栈

原生 Android（Java）· Room（12 版保数据迁移）· CameraX + ML Kit（条形码识别）·
Retrofit · Glide · 零依赖手写 XLSX 读写器（不引入 POI）· EncryptedSharedPreferences（API 密钥加密存储）
当前版本 v4.15（versionCode 54，Room 数据库 v13）。

### 构建

Android Studio 打开本目录直接同步即可；或命令行：

```
./gradlew :app:assembleDebug
```

交付物即为 debug 构建产物（v4.1 起不再区分测试版/正式版，功能一致）。
要求：JDK 17+、Android SDK Platform 36、Gradle 9.4.1（wrapper 已指定）。
Room schema 导出于 `app/schemas/`（迁移比对与测试用）。

### ISBN 数据源配置

ISBN 数据源在「我的 → API 设置」中填写，支持三种鉴权方式（默认腾讯云市场签名）。
密钥使用 AndroidKeyStore 加密存储，不会明文落盘。
这个 API 可以到腾讯云或阿里云搜索 ISBN，然后找到 ISBN 图书查询类似的服务购买即可，部分云服务是免费的但是数据不全或错误，可自行探索。
已适配腾讯云市场「聚美智数 ISBN 书号查询」（产品 ID 34136）（我目前使用的就是这个 https://market.cloud.tencent.com/products/34136 ）：

1. 鉴权方式选「腾讯云市场签名（SecretId+SecretKey）」
2. 请求地址填 API 接口调用地址，例如 `https://ap-guangzhou.cloudmarket-apigw.com/service-ed62t9xo/isbn/query`
3. SecretId / SecretKey 在腾讯云控制台 → 云市场（我的资源） → 已购产品与服务 → 管理 → 应用信息
4. 未配置时不影响使用：扫码后直接进入手动录入

</details>

## 📄 使用条款与免责声明

- 仅供个人学习与非商业用途。
- 数据仅保存在本机，卸载或清除数据将导致藏书记录丢失，请定期使用 zip 自动备份并妥善保存备份文件。
- 图书信息与豆瓣评分来自第三方公开接口/网页，其准确性与可用性由提供方负责；豆瓣非官方接口，可能随其改版失效。
- 请遵守数据源服务商的服务条款，勿将接口用于商业用途。

## ©️ 版权

Copyright (c) 2026 bai. All rights reserved.
