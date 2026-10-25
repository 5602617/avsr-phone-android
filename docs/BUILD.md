# shealive 构建与发行准备

## 工具链

JDK 25、Gradle wrapper 9.7.1、AGP 9.4.1、Kotlin/Compose plugin 2.4.20；Android compile/target 37、min 33。SDK 包为 `platforms;android-37.0` 与 `build-tools;37.0.0`。原生程序使用 NDK 28.2.13676358、Go 1.25.6。

设置 `ANDROID_HOME` 或本机 `local.properties`；该文件不分发。macOS/Linux 需要 bash、Python3、curl、tar、shasum 及上述原生工具链。Windows 可打包 Android，但原生重建脚本只支持 macOS/Linux。

## 完整本地构建

六个原生程序未跟踪于 Git，干净 checkout 必须先构建或准备可信且与现有清单一致的副本。

```sh
export JAVA_HOME=/absolute/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/absolute/path/to/android-sdk
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk/28.2.13676358
bash scripts/build-tunnels.sh
python3 scripts/native-manifest.py --verify
bash ./gradlew :app:assembleDebug :app:assembleRelease
```

不依赖脚本 executable bit。Windows 在准备原生程序后用 `gradlew.bat` 替代 `bash ./gradlew`。Debug 产物为 `app/build/outputs/apk/debug/app-debug.apk`，未配签名时 Release 为 `app/build/outputs/apk/release/app-release-unsigned.apk`。不含原生程序的构建不能当作完整候选。

原生入口下载固定标签 frp 0.65.0 / cloudflared 2025.8.1，验证源码归档 SHA256，应用 `scripts/patch-native.py`，构建 API33 的 arm64-v8a、armeabi-v7a、x86_64。`libfrpc.so` / `libcloudflared.so` 是可执行 Android ELF PIE，保留名称和打包方式，不 strip。来源、补丁和二进制哈希写入 `app/src/main/assets/native-build.json`。

重建结束自动核验六个文件；单独 `python3 scripts/native-manifest.py --verify` 只读检查完整性、大小、SHA256 和补丁哈希，不写清单。复用已有可信文件时只核验，不生成新清单冒充原来源。哈希核对不代表所有 ABI 已经实机验证，也不承诺跨主机字节完全一致。

替换或修改随包的 LGPL 组件 spake2，见 [重建与重新链接](RELINKING.md)。依赖的许可证、来源和所选许可见 [第三方通知](../THIRD_PARTY_NOTICES.md)。

## 签名与升级

APK 应用身份保持 `dev.avsr.phone`，versionCode 13，versionName 0.1.0。项目文档显示名为 shealive，不更换包名或签名。

维护者可用现有环境入口签名：

```sh
export AVS_KEYSTORE=/absolute/path/to/existing-release-keystore
export AVS_KEY_ALIAS=existing-key-alias
# 通过本机安全方式设置 AVS_STORE_PASSWORD、AVS_KEY_PASSWORD，不写入仓库。
bash ./gradlew :app:assembleRelease
```

未配密钥的 unsigned Release 不能直接安装。Debug 使用独立开发身份；其他维护者自签也不能覆盖已有发行身份。 Debug 的应用 ID 为 `dev.avsr.phone.debug`：连接宿主时由管理员把插件 `apk_package` 改成同值，保存并重新加载插件，才能完成设备工具登记；正式签名或自行签名的 Release 均保持 `dev.avsr.phone` 与插件默认值，不修改稳定应用 ID。不要为解决签名差异卸载用户应用或清数据。

维护者的发行证书 SHA256 为 `deca00a4eae839656b9371b7cd48868f727c532f1c31425e4e692a3ec1c56eda`。它不沿用上游旧发行证书或签名轮换链。源码构建不需要维护者的私钥；自行签名的 APK 使用自己的身份，升级已有安装需保持原签名。

下载后先比对发行页的 `SHA256SUMS`，再用 Android SDK 检查签名：

```sh
apksigner verify --verbose --print-certs shealive-0.1.0.apk
```

证书指纹与文件 SHA256 是两项不同的信息：前者识别签名，后者识别这一份产物。发行页须同时提供二者。

## CI 与发行边界

`.github/workflows/build.yml` 的 verify/release 两个 job 均安装固定 Go/NDK，先通过 bash 构建六个隧道程序并核验清单，再运行 Gradle；不能依赖开发机未跟踪 .so。Actions 固定到提交，setup-go 使用 v6 对应提交 `924ae3a1cded613372ab5595356fb5720e22ba16`。本地仅验证配置/脚本语法、路径和清单场景，尚无当前工作流完整运行通过证据。

分支和 PR 触发构建；`v*` 标签在构建通过后准备 **草稿 Release**，不会自动公开。标签必须与 `versionName` 一致，并有对应 `docs/RELEASE_NOTES_<版本>.md`。首个公开版本为 `0.1.0`。

标签构建需由维护者在目标仓库设置 `AVS_KEYSTORE_BASE64`、`AVS_STORE_PASSWORD`、`AVS_KEY_PASSWORD`、`AVS_KEY_ALIAS`。使用已有发行密钥，不创建或沿用上游 lineage；流水线启用 v2/v3 签名，并核对上述证书指纹。文件名统一为 `shealive-<版本>.apk`。源码仓不包含签名材料；此说明不表示远端 Secrets 已配置或流水线已执行。

维护者审阅草稿中的 APK、源码标签、签名指纹、许可材料和版本说明后，再执行公开发布。当前 CI 不自动准备 spake2 对应源码附件；发布草稿前须按 [RELINKING](RELINKING.md) 补齐包含 C 子模块的固定源码归档及其 SHA256。每次发行需从对应提交的干净检出构建，准备并核对清单列明的原生输入；普通工作区构建不代替这一步。当前工作流的完整云端运行尚未验证。

首版发行内容为签名 APK、`SHA256SUMS`、`SIGNING-CERTIFICATE.txt`、对应应用源码版本、spake2 完整对应源码归档及 [0.1.0 说明](RELEASE_NOTES_0.1.0.md)。版本说明保留已知限制。许可资产或应用输入改变后，须重建产物并更新哈希。
