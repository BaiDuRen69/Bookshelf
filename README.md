# 小书房 Bookshelf

个人/家庭纸质藏书管理 Android 应用（参照微信小程序「我是小书房啊」重做）。
当前版本 v4.6（versionCode 45，Room 数据库 v12）。

## 功能

- 扫书脊 ISBN 条形码录入，自动补全书名/作者/出版社/封面（支持连扫）
- 三视图书架（列表/封面网格/纯文字）、搜索、多维筛选与 11 种排序、6 套主题
- 逐本副本管理（多本藏书、各本独立备注/图片/存放位置）、借阅登记与归还、借阅人库
- 藏书统计（总览、藏书之最、我的阅读、作者/出版社/国家/分类分布可点击跳转）
- 套装分摊、备注图片附件、自动备份（zip 全量，保留最近 5 份）
- 豆瓣评分：扫码后自动获取，编辑页也可一键查询补录

## 数据与备份（重要）

- **Excel 导出**：便于查看/分享的藏书清单（含内嵌封面图、封面路径列）。
  ⚠️ 它**不是全量备份**——逐本副本、借出记录、借阅人、备注图片不参与 Excel 导入导出。
- **zip 自动备份 / 手动恢复**：真正的全量备份（data.json 全表 + media/ 图片），
  可跨设备完整恢复。请以此作为唯一可靠的备份手段。

## 技术栈

原生 Android（Java）· Room（12 版保数据迁移）· CameraX + ML Kit · Retrofit · Glide ·
零依赖手写 XLSX 读写器（不引入 POI）· EncryptedSharedPreferences（API 密钥加密存储）

## 使用前配置

ISBN 数据源在「我的 → API 设置」中填写，支持三种鉴权方式（默认腾讯云市场签名）。
密钥使用 AndroidKeyStore 加密存储，不会明文落盘。

已适配腾讯云市场「聚美智数 ISBN 书号查询」（产品 ID 34136）：

1. 鉴权方式选「腾讯云市场签名（SecretId+SecretKey）」
2. 请求地址填 `https://ap-guangzhou.cloudmarket-apigw.com/service-ed62t9xo/isbn/query`
3. SecretId / SecretKey 在腾讯云控制台 → 云市场 → 已购产品与服务 → 管理 中查看
4. 未配置时不影响使用：扫码后直接进入手动录入

## 构建

Android Studio 打开本目录直接同步即可；或命令行：

```
./gradlew :app:assembleDebug
```

交付物即为 debug 构建产物（v4.1 起不再区分测试版/正式版，功能一致）。
要求：JDK 17+、Android SDK Platform 36、Gradle 9.4.1（wrapper 已指定）。
Room schema 导出于 `app/schemas/`（迁移比对与测试用）。

## 问题反馈

欢迎提交 Bug 与功能建议（App 内入口：我的 → 问题反馈）：

- GitHub Issues：https://github.com/BaiDuRen69/Bookshelf/issues
- 邮箱：baiduren69@outlook.com

## 使用条款与免责声明

- 仅供个人学习与非商业用途。
- 数据仅保存在本机，卸载或清除数据将导致藏书记录丢失，请定期使用 zip 自动备份并妥善保存备份文件。
- 图书信息与豆瓣评分来自第三方公开接口/网页，其准确性与可用性由提供方负责；豆瓣非官方接口，可能随其改版失效。
- 请遵守数据源服务商的服务条款，勿将接口用于商业用途。

## 版权声明

Copyright (c) 2026 bai. All rights reserved.
