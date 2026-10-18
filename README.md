# 小书房 Bookshelf

个人/家庭纸质藏书管理 Android 应用（参照微信小程序「我是小书房啊」重做）。

## 功能

- 扫书脊 ISBN 条形码录入，自动补全书名/作者/出版社/封面（支持连扫）
- 搜索、状态/地点筛选、排序
- 存放地点管理、借阅登记与归还
- 藏书统计（总览、藏书之最、作者/出版社分布）
- 一键导出 Excel（含 ISBN，可用于备份和迁移）

## 技术栈

原生 Android（Java）· Room · CameraX + ML Kit · Retrofit · Glide
Excel 导出为零依赖的极简 XLSX 写入器，不引入 POI。

## 使用前配置

ISBN 数据源在「我的 → API 设置」中填写，支持三种鉴权方式（默认腾讯云市场签名）。

已适配腾讯云市场「聚美智数 ISBN 书号查询」（产品 ID 34136）：

1. 鉴权方式选「腾讯云市场签名（SecretId+SecretKey）」
2. 请求地址填 `https://ap-guangzhou.cloudmarket-apigw.com/service-ed62t9xo/isbn/query`
3. SecretId / SecretKey 在腾讯云控制台 → 云市场 → 已购产品与服务 → 管理 中查看
4. 未配置时不影响使用：扫码后直接进入手动录入

## 构建

Android Studio 打开本目录直接同步即可；或命令行：

```
gradle :app:assembleDebug
```

要求：JDK 17+、Android SDK Platform 36、Gradle 9.4.1（wrapper 已指定）。

## 使用条款与免责声明

- 仅供个人学习与非商业用途。
- 数据仅保存在本机，卸载或清除数据将导致藏书记录丢失，请定期导出 Excel 备份。
- 图书信息来自第三方 ISBN 数据接口，其准确性与可用性由接口提供方负责。

## 版权声明

Copyright (c) 2026 bai. All rights reserved.
