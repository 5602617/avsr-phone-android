# 贡献说明 · Contributing

## 中文

欢迎修复问题、改进界面与文档。较大的交互或协议变更请先在仓库公开后的 [Issues](https://github.com/5602617/avsr-phone-android/issues)说明场景和方向；小修可以直接提出 PR。普通反馈请包含客户端/插件版本、Android/机型和复现步骤。涉及安全问题请使用 [SECURITY](SECURITY.md) 中的报告方式。

### 准备开发环境

```sh
git clone https://github.com/5602617/avsr-phone-android.git
cd avsr-phone-android
```

仓库尚在准备首次公开；公开后可使用上述克隆入口。准备 JDK 25、Android SDK 37，并按 [BUILD](docs/BUILD.md)准备原生程序输入，然后执行 `bash ./gradlew :app:assembleDebug`（Windows 使用 `gradlew.bat`）。完整构建前需要准备原生程序，不要把仅有 APK 文件的缓存构建当成完整发行包。开发版本使用独立 Debug 应用身份，便于和既有发行安装并存。

### 改动与验证

本仓维护 shealive 的 Android 手机客户端。主要源码在 `app/src/main/kotlin/io/github/lejw0925/virtualscreen/`：`app/` 管应用状态与头像存储，`phone/` 管宿主连接和手机交互，`ui/` 管界面，`core/`、`backend/`、`mcp/` 与 `tunnel/` 管设备执行和底层接口。资源在 `app/src/main/res/`，原生程序来源与构建脚本在 `scripts/`；`device-fixture/` 是设备验证用应用。

开发环境、完整原生构建和 APK 命令见 [BUILD](docs/BUILD.md)。提交改动时说明具体行为、相关检查与未覆盖范围；不要把本机缓存构建等同于外部干净构建或设备验收。

界面及手机端状态在本仓修改；宿主账号、提供者配置与插件适配在独立 `astrbot_plugin_avsr_phone` 仓。客户端—宿主协议以[插件仓 `docs/protocol/`](https://github.com/5602617/astrbot_plugin_avsr_phone/tree/main/docs/protocol) 为准，改帧或能力字段时同步两端合同，不在本仓另建一份竞争协议。用户文案与现有三语 UI 保持对应。

使用现有 Kotlin 风格与目录职责，保持英文、简体和繁体 UI 对齐。Kotlin namespace 继承上游；显示名调整不需要改 namespace 或安装身份，详情见 [UPSTREAM](docs/UPSTREAM.md)。

选择与改动有关的检查：JVM 单元测试 `:app:testDebugUnitTest`、Android Lint `:app:lintDebug`，或 BUILD 中的设备检查；设备操作需在明确选择的设备和授权范围内进行。纯文档改动核对链接、命令与现行实现即可。新增验证应覆盖实际行为和边界，PR 写清执行了什么，以及未覆盖范围。

### 提交 PR

说明要解决的具体问题、改变后的行为和相关验证。UI 改动可附真实、脱敏且经同意公开的截图；不要用私人图片充当演示资产。协议改动说明两端兼容策略。保持提交聚焦，不混入无关缓存、日志或其他人的改动。当前仓库已有 Git 历史，应保留来源与现有工作。

保留 [上游来源](docs/UPSTREAM.md)、[LICENSE](LICENSE) 和 [第三方通知](THIRD_PARTY_NOTICES.md)。新增或变更依赖、字体、原生程序时记录实际来源与许可；不要把上游实现写成新原创。

不要提交签名密钥、口令、账号令牌、配对资料、本机 `local.properties`、数据库、用户聊天/头像、未审截图或运行日志。原生二进制和构建产物沿现有构建/发行入口处理；用户导入图片不是测试素材或发行资产。

## English

Contributions to behavior, UI and documentation are welcome. Once the repository is public, discuss substantial interaction/protocol changes in [Issues](https://github.com/5602617/avsr-phone-android/issues); small fixes can go directly to a PR. Include client/plugin versions, Android/device and reproduction steps in bug reports. Follow [SECURITY](SECURITY.md) for security issues.

Clone `https://github.com/5602617/avsr-phone-android.git` after first publication. Prepare JDK 25, Android SDK 37 and the native inputs in [BUILD](docs/BUILD.md), then use `bash ./gradlew :app:assembleDebug` (`gradlew.bat` on Windows). Debug has a separate application identity. Native inputs are required for a complete build.

This repository maintains the shealive Android phone client. Sources live under `app/src/main/kotlin/io/github/lejw0925/virtualscreen/`: `app/` contains app state and face storage, `phone/` host connections and phone interactions, `ui/` the interface, and `core/`, `backend/`, `mcp/` and `tunnel/` device execution and underlying interfaces. Resources are in `app/src/main/res/`, native build scripts in `scripts/`, and the device test application in `device-fixture/`.

See [BUILD](docs/BUILD.md) for the toolchain, native programs and APK commands. Describe the behavior changed, checks performed and remaining coverage; a cached local build is not a clean external build or device acceptance.

Phone UI and client state belong here. Host accounts, provider configuration and plugin adaptation belong in the separate `astrbot_plugin_avsr_phone` repository. Its [docs/protocol](https://github.com/5602617/astrbot_plugin_avsr_phone/tree/main/docs/protocol) owns the client–host contract. Coordinate frame or capability changes across both ends instead of creating a competing protocol document. Keep UI text aligned across the existing three languages.

Preserve [upstream attribution](docs/UPSTREAM.md), [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md). Record sources and licenses for dependency, font or native-program changes. Do not claim upstream work as new original work.

Do not commit signing keys, passwords, account tokens, pairing material, local SDK configuration, databases, user chats or pictures, unreviewed screenshots, or runtime logs. Use the existing build/release flow for native binaries and artifacts; imported user images are not test or release assets.

Follow existing Kotlin style and keep all three UI languages aligned. The Kotlin namespace is inherited from upstream; branding does not require changing it or the installation identity. Select relevant JVM tests (`:app:testDebugUnitTest`), Lint (`:app:lintDebug`) or authorized device checks. For documentation-only changes, check links and content against current behavior.

PRs should explain the concrete problem, changed behavior and verification, including untested scope. Add real, sanitized and approved screenshots for UI changes when available, and describe compatibility for protocol changes. Keep commits focused and preserve existing Git history and unrelated work.
