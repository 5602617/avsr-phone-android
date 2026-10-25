# 上游与主要改动

## 直接上游

shealive 手机客户端基于 [lejw0925/android-virtual-screen](https://github.com/lejw0925/android-virtual-screen) 开发，导入基线为 **v0.1.1**（commit `112d313ca7e99ece643d412fc136b880fbac1db5`）。

沿用的基础包括独立 Android 屏幕、MCP 服务、画面捕获、输入控制、预览与接管。

## 主要改动

- 手机聊天、录音、语音播放、浮窗及配套手机界面。
- 与 AstrBot 手机插件的登录、连接、消息传输和能力协商接线。
- 按 Persona 保存图片槽，消费宿主标签清单，提供头像映射、折叠和本机栏位移除。
- 手机端借屏许可、用户接管、停止及系统确认等交互，与原设备执行和授权路径整合。
- 设备连接、状态传递与资源释放的适配和修复。

## 技术身份与合同

对外显示名为 shealive，仓库为 [avsr-phone-android](https://github.com/5602617/avsr-phone-android)。发行应用 ID 保持 `dev.avsr.phone`；Kotlin namespace `io.github.lejw0925.virtualscreen` 保留上游布局，便于维护继承代码。namespace 不等于 Android 安装身份；更换显示名不要求迁移包目录或重置签名。

宿主侧为 [astrbot_plugin_avsr_phone](https://github.com/5602617/astrbot_plugin_avsr_phone)，其 [docs/protocol](https://github.com/5602617/astrbot_plugin_avsr_phone/tree/main/docs/protocol) 是手机协议的唯一维护入口。本仓消费协议，不复制协议正文。

## 来源与许可

部分虚拟屏、捕获、输入和旋转实现源自蛮吉（Mangi-11）的 [ETA](https://github.com/Mangi-11/Eta)。项目以 [PolyForm Noncommercial 1.0.0](../LICENSE) 提供源码，属于源码可见的非商业许可；保留以下上游声明：

Required Notice: Copyright © 2026 蛮吉 (Mangi-11).

其他组件与许可见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
