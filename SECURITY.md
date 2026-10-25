# 安全与隐私 · Security

## 报告安全问题

请通过 GitHub 的[私有漏洞报告入口](https://github.com/5602617/avsr-phone-android/security/advisories/new)提交安全问题。该入口需由维护者在仓库启用 **Private vulnerability reporting**；若入口暂不可用，请通过已有的私下联系渠道联系维护者，切勿改用公开 Issue 发送敏感内容。本文不承诺响应时限。

普通功能问题可以在公开后通过 [Issues](https://github.com/5602617/avsr-phone-android/issues)报告。提供客户端和插件版本、Android 版本、影响及脱敏后的复现信息。

## 使用边界

- 客户端连接由你指定的宿主。宿主处理消息，识别与合成提供者可能接收音频/文本；请了解宿主及其提供者的数据处理安排。
- 麦克风用于录音，悬浮窗用于应用外交互，通知用于配对和提醒，无障碍和已配对的特权服务用于设备观察/操作。仅为你信任的宿主启用这些权限。
- 功能协商与具体操作授权分开。借屏、设备授权和接管沿应用内卡片处理；文字回复“允许”不代替授权卡上的点按。
- 操作时可停止或接管；主屏使用方式在设置中选择。无线调试及后台服务受 Android 系统控制，系统设置也可关闭相关权限。
- 局域网 `http://` 不提供 TLS 传输保护，只在可信网络使用。公网部署应由管理员配置正确的 HTTPS，客户端不会自动为宿主创建 TLS。

维护或报告时不要上传签名密钥、密码、认证令牌、配对码、个人图片、聊天数据库和未脱敏日志。发现凭据泄漏时先通过相应宿主/账号管理入口更换凭据。

## Reporting in English

Please report security issues through GitHub's [private vulnerability reporting page](https://github.com/5602617/avsr-phone-android/security/advisories/new). The maintainer must enable **Private vulnerability reporting** for this repository. If the page is unavailable, contact the maintainer through an existing private channel; do not disclose vulnerability details or private data in public issues. No response deadline is promised.

The host and its configured providers process chat and audio. Grant microphone, overlay, Accessibility and paired-service access only for a host you trust. Capability negotiation does not approve an individual action. Use the phone's consent cards, stop and handoff controls. Local HTTP is unencrypted; public hosts need administrator-configured HTTPS. Keep credentials, pairing data, signing keys, chats, pictures and unredacted logs out of reports.
