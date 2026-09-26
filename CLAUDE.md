# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Gotify Android 客户端(Kotlin),连接 [gotify/server](https://github.com/gotify/server),通过常驻 WebSocket 前台服务接收推送通知。UI 使用传统 View 体系 + ViewBinding + Material Design(非 Compose)。

## 构建命令

需要 Java 17。Windows 环境下用 `gradlew.bat`,POSIX shell 下用 `./gradlew`。

```bash
./gradlew build                 # 完整构建 + lint + ktlint 检查(CI 即此命令)
./gradlew assembleDebug         # Debug APK
./gradlew assembleDevelopment   # 带 .dev 后缀包名的开发版 APK(可与正式版共存)
./gradlew lintKotlin            # 仅运行 ktlint 检查
./gradlew formatKotlin          # 自动格式化 Kotlin 代码
./gradlew openApiGenerate       # 重新生成 client 模块(见下)
```

本项目没有单元测试或 instrumentation 测试(`app/src` 下只有 `main`)。发布签名通过 `-Psign` 属性加 `RELEASE_STORE_FILE` 等环境变量控制,仅 CI 打 tag 时使用。

## 关键约束

- **`client/` 模块是自动生成的代码**,由 OpenAPI Generator 从 gotify/server 的 `docs/spec.json` 生成(根 `build.gradle.kts` 会先从服务器仓库 master 分支下载 spec)。不要手动编辑 `client/` 下的任何文件;API 变更时应运行 `./gradlew openApiGenerate` 并提交生成结果。
- Kotlin 代码风格由 ktlint 强制(`.editorconfig` 中 `ktlint_code_style = android_studio`),`build` 会失败于格式问题,提交前跑 `formatKotlin`。
- app 模块内广泛使用 `internal` 可见性修饰符,新增代码应保持这一惯例。

## 架构

两个 Gradle 模块:`:app`(全部手写代码)和 `:client`(生成的 Retrofit/OkHttp API 层)。

### 启动流程

`GotifyApplication`(初始化 tinylog、主题、前台通知渠道、旧证书迁移)→ `InitializationActivity`(launcher,持 splash):检查 token → 请求通知/精确闹钟权限 → 验证认证 → 进入 `MessagesActivity`,未认证则进入 `LoginActivity`(支持用户名密码与 OIDC PKCE 两种方式)。

### 消息数据流(核心)

两条并行通路汇合到消息列表:

1. **实时**:`WebSocketService`(specialUse 前台服务)维持与服务器的 WebSocket 连接,监听网络变化自动重连;收到新消息后发 `NEW_MESSAGE_BROADCAST` 广播(debug 构建的 action 带 `.DEBUG` 后缀),由 `MessagesActivity` 接收。断线期间的消息由 `MissedMessageUtil` 通过 REST 补拉。
2. **REST 分页**:`MessagesModel`(ViewModel,持有全部依赖)→ `MessageFacade`(`@Synchronized` 门面)组合 `MessageRequester`(分页 API 请求)与 `MessageStateHolder`(按 appId 缓存消息状态),`MessageImageCombiner` 把应用图标拼进消息条目。

### 横切关注点

- **配置**:`Settings` 是 SharedPreferences("gotify")的薄封装(url、token、SSL 设置、OIDC 状态等),各处直接实例化传入。
- **API 客户端**:`ClientFactory` 负责按认证方式(无认证/Basic/clientToken)构造 Retrofit `ApiClient`;自定义 CA 与客户端证书经 `CertUtils.applySslSettings` 注入 OkHttp。
- **日志**:tinylog,`LogsActivity` 供用户在应用内查看;`UncaughtExceptionHandler` 把崩溃写入日志。
- **通知渲染**:`NotificationSupport` 管理通道;消息正文中的 Markdown 由 Markwon 渲染(见 `MarkwonFactory`),图片经 Coil(含 SVG 支持,见 `CoilInstance`)。
- **消息增强**:服务器消息的 `extras` 字段解析在 `messages/Extras.kt`(客户端展示、通知铃声/振动等均由 extras 驱动)。
