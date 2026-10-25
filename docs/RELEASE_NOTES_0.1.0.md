# shealive 0.1.0

首个公开版本 **0.1.0**：[发行页与版本说明](https://github.com/5602617/avsr-phone-android/releases/tag/v0.1.0) · [签名 APK](https://github.com/5602617/avsr-phone-android/releases/download/v0.1.0/shealive-0.1.0.apk) · [SHA256SUMS](https://github.com/5602617/avsr-phone-android/releases/download/v0.1.0/SHA256SUMS) · [SIGNING-CERTIFICATE.txt](https://github.com/5602617/avsr-phone-android/releases/download/v0.1.0/SIGNING-CERTIFICATE.txt) · [v0.1.0 应用源码](https://github.com/5602617/avsr-phone-android/tree/v0.1.0) · [spake2-2.2.1 对应源码附件](https://github.com/5602617/avsr-phone-android/releases/download/v0.1.0/spake2-2.2.1-corresponding-source.tar.gz)。

## 本版变化

- 新增手机文字聊天、录音与语音回复，支持浮窗交互和文字/语音回复偏好。
- 提供登录与首次设置引导；可以先进入聊天，之后再准备手机操作所需配对和权限。
- 在“她的屏幕”查看后台操作；借用主屏时可确认、拒绝或取消，也可以在需要时接管、交还或停止操作。
- “她的头像”按人格保存普通图、图片槽和标签分配，跟随宿主的回复标签切换图片。未知标签回到普通图。
- 头像区域和图片槽标签可折叠；闲置人格的本机头像栏可经确认移除，原图、其他人格、聊天和任务保留。
- 客户端与宿主按功能协商聊天、语音和手机控制；设备尚未准备好时仍可使用可用的聊天功能。
- 修正主动释放控制权被误判为意外结束的问题，保留明确的借屏授权路径。

## 配套与范围

| 组件 | 版本/范围 |
| --- | --- |
| Android 客户端 | 0.1.0，Android 13+ |
| AstrBot 手机插件 | `astrbot_plugin_avsr_phone` 0.1.0 |
| 原生 AstrBot | 已验证组合为 4.29.3 |
| 手机协议 | v1，按功能协商；[维护入口](https://github.com/5602617/astrbot_plugin_avsr_phone/tree/main/docs/protocol) |
| AVSR 扩展宿主 | 当前组合回归待完成 |

原生 AstrBot 组合已覆盖文本、语音、头像、主屏授权/操作、交接和交互等待；这不是所有 Android 设备、ABI 或宿主组合的兼容承诺。在途停用等未覆盖场景不列为已验证。

## 已知限制

- 语音取决于宿主配置的 STT/TTS；头像标签和角色信息由宿主提供，无预装人物图片套组。
- 客户端不自行提供持久任务、记忆或主动唤醒。原生 AstrBot 的交互等待不承诺跨重启恢复。
- 无线调试、后台常驻、受保护页面和应用在不同屏幕的行为受 Android 系统及具体应用限制。
- 本版已从固定提交源码完成本地 Release 构建，并核对六个原生程序输入及发行签名；使用已有依赖缓存，没有把它称为全新环境下载验证。当前云端 CI 和全部 ABI 的设备运行尚未完成验证。

## 安装与升级

首次使用见[快速开始](../README.md#快速开始)。应用身份保留 `dev.avsr.phone`；覆盖更新须保持兼容签名。自行签名的构建不能直接替换其他签名的安装。遇到签名或版本冲突先核对安装来源，不要用卸载或清数据规避。

构建见 [BUILD](BUILD.md)，来源见 [UPSTREAM](UPSTREAM.md)。本客户端沿用 [PolyForm Noncommercial 1.0.0](../LICENSE)；随包第三方组件见[第三方通知](../THIRD_PARTY_NOTICES.md)。
