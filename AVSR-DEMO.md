# 004 手机能力候选

2026-10-22：按用户指定先实现虚拟屏通用操作与 QQ 音乐本机播放控制。当前源码是个人非商业测试候选；真实执行记录和未完成项以 `D:/AVSR/specs/004-persona-phone-entry/report.md` 为准。

## 来源与改动

- 上游：<https://github.com/lejw0925/android-virtual-screen>，v0.1.1，commit `112d313ca7e99ece643d412fc136b880fbac1db5`。
- 原源码压缩包 SHA256：`e82cad910fb0724198037311cfa2b373aefbdf5ce1d8b9c787f7fb2c05d0d09c`。
- 保留上游 `LICENSE`、原生隧道程序及来源说明。上游为 PolyForm Noncommercial 1.0.0；本轮不授权商业使用、公开发布或更换许可。
- 取消后台服务取得远程租约后自动展示预览；Activity 恢复/焦点变化不再触发接管。用户触摸虚拟屏或点击接管才取得手机控制，点击交还后释放。
- 新增 QQ 音乐专用 MediaSession 控制器、通知访问服务和手机操作页；通过同一手机 MCP 服务器提供状态读取、暂停、继续、跳进度。
- 修复预览生命周期回调读取可变 view 的问题：旧回调只结束其捕获的渲染器，避免把新渲染器的输入关闭。
- QQ 实现使用 Android 公共接口，未复制同频项目受限代码。仅筛选 `com.tencent.qqmusic`，按读取返回的会话 ID 执行；不切换 Activity、不使用无障碍搜歌、不发送全局媒体按键。
- 精确目标和从真实 MCP `tools/list` 导出的 schema 位于插件 `app-adapters/qqmusic/target.json`、`tools.json`；QQ 20.9.0.8 已经执行直连诊断。暂停时 seek 会恢复播放，结果如实读回，不承诺保持暂停。
- 调试包名 `io.github.lejw0925.virtualscreen.avsr`，版本后缀 `-avsr-demo1`；与原安装包共存，不覆盖或清空原包数据。两者 MCP 默认端口相同，验证只开启选中候选。

## 构建与范围

使用上游 JDK 25、Gradle 9.7.1、Android SDK 37。本候选显式固定 Build Tools 37.0.0。

```powershell
$env:JAVA_HOME='D:/AVSR/.cache/phone-toolchain/jdk25'
$env:GRADLE_USER_HOME='D:/AVSR/.cache/phone-toolchain/gradle'
$env:ANDROID_USER_HOME='D:/AVSR/.cache/phone-toolchain/android-user'
./gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

SDK 路径在本地 `local.properties`，不作为他方固定路径要求；SDK/JDK 缓存不随候选打包。默认 APK 路径为 `app/build/outputs/apk/debug/app-debug.apk`。

虚拟屏需要实际可用的 Root/Shizuku；QQ 工具需要通知访问授权和已初始化的 QQ 音乐播放会话。QQ 已授权时，MCP 开启不再要求虚拟屏后端同时已授权；各工具执行时仍校验自身实际依赖。第 2 轮已实现 AVSR 主动连接/设备绑定/原 MCP manager 接线及任务 UI，并取得原 Persona/Core 的 QQ 首页读取、结果回传和接管拒绝记录。当前候选为 `0.1.1-avsr-demo2.2`（code 7）；phone 域 PromptExtension 公共接入缺口仍未实施。操作顺序、真实失败和最终验收范围以原 report 为准。

## QQ 工具合同

`qqmusic_get_playback`：沿既有 MCP 要求填写 `purpose`，返回 QQ 会话列表，包括 `session_id`、`package`、`media_id/title/artist`、`position_ms`、`playback_state`、支持动作和观测时间。没有通知权限或没有会话时如实返回。

`qqmusic_control_playback`：`session_id` 使用前次真实读取值；`command` 为 `play`、`pause` 或 `seek`，只有 seek 携带非负 `position_ms`；同时填写 `purpose`。每次只发送一次命令，再读取手机状态最多约 3 秒；返回 before/after 和 effect_status，超时保留已发送、效果未确认，不自动重发。

工具描述暂为两个 `TODO(prompt)` 占位，位置在 `mcp/McpToolRegistry.kt`。按最新分工由 Claude 与用户补写，当前不进行自主模型演示。手机 UI 上的按钮是手动诊断入口，不替代原 Persona/Core 链路。

## 检查边界

现有 `DevicePreviewTest` 已按静默取得控制、主动展开后保留远程控制调整；JVM 租约/授权回归及 lint 只是代码检查。设备直调、ADB 转发和手动页面验证只作为定位证据，不能代替完整 APK 主动连接与原 Persona 的最终验收。具体执行情况在原 report 登记，不在本文件维护任务完成标记。
