# 来源与许可

本客户端基于 [lejw0925/android-virtual-screen](https://github.com/lejw0925/android-virtual-screen) 开发，导入基线为 v0.1.1。版本记录与主要改动见 [UPSTREAM.md](docs/UPSTREAM.md)。

虚拟屏、捕获、输入、旋转等部分代码改编自 [Mangi-11/Eta](https://github.com/Mangi-11/Eta)，保留 ETA 的 PolyForm Noncommercial 1.0.0 许可和 Required Notice。对应许可见根目录 `LICENSE` 与 `app/src/main/assets/licenses/ETA.txt`。

Required Notice: Copyright © 2026 蛮吉 (Mangi-11).

| 组件 | 来源 | 许可文件 |
| --- | --- | --- |
| AndroidX Compose Material 3 / Material Icons | [androidx/androidx](https://github.com/androidx/androidx) | `assets/licenses/AndroidX.txt`，Apache-2.0 |
| miuix 0.9.4（历史 UI；迁移后保留通知） | [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix/tree/v0.9.4) | `assets/licenses/miuix.txt`，Apache-2.0 |
| frpc 0.65.0 | [fatedier/frp](https://github.com/fatedier/frp/tree/v0.65.0) | `assets/licenses/frp.txt`，Apache-2.0 |
| cloudflared 2025.8.1 | [cloudflare/cloudflared](https://github.com/cloudflare/cloudflared/tree/2025.8.1) | `assets/licenses/cloudflared.txt`，Apache-2.0 |

原生补丁位于 `scripts/patch-native.py`，构建脚本位于 `scripts/build-tunnels.sh`。源码归档、补丁与三 ABI 可执行文件的 SHA-256 记录在 `app/src/main/assets/native-build.json`。App 的「关于 → 开源许可与构建来源」展示组件许可文本；构建来源与二进制哈希保留在仓库和 APK 资产中，避免向普通用户展示 JSON。

## 当前直接运行依赖

以下按当前 app/build.gradle.kts 的 implementation 声明建立来源索引；完整坐标/版本与 APK 内许可文本对应关系见 app/src/main/assets/licenses/dependencies-index.txt。测试与构建工具不作为 APK 运行依赖列入；本表不声称覆盖全部传递依赖。

| 组件与直接版本 | 主要上游来源/许可依据 | APK 内文本 |
| --- | --- | --- |
| libadb-android 3.1.1 | [上游 COPYING](https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/COPYING)，GPL-3.0-or-later OR Apache-2.0；本发行采用 Apache-2.0 选项，其他部分沿上游 LICENSES | libadb-COPYING.txt；AndroidX.txt |
| Conscrypt Android 2.5.3 | [google/conscrypt](https://github.com/google/conscrypt/tree/2.5.3)，Apache-2.0，保留原 NOTICE | Conscrypt-NOTICE.txt；AndroidX.txt |
| Bouncy Castle bcpkix-jdk15to18 1.81 | [1.81 许可](https://github.com/bcgit/bc-java/blob/r1rv81/LICENSE.html)，Bouncy Castle License（MIT 风格） | BouncyCastle.txt |
| AndroidX Activity Compose 1.13.0；Lifecycle runtime/viewmodel Compose 2.11.0；Material3 1.5.0-alpha29；Material Icons Extended 1.7.6 | [AndroidX 源码](https://cs.android.com/androidx/platform/frameworks/support)，Apache-2.0 | AndroidX.txt |
| kotlinx-coroutines-android 1.11.0 | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)，Apache-2.0 | AndroidX.txt |
| Stellar-API 1.0.3 及 vendored userservice | [上游 README 许可节](https://github.com/roro2239/Stellar-API#readme)：Stellar 修改部分 MPL-2.0，原 Shizuku 部分 Apache-2.0；本地 4 份 vendored 文件保留来源/版本头 | Stellar.txt；AndroidX.txt |
| HiddenApiBypass 6.1 | [LSPosed/AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE)，Apache-2.0 | AndroidX.txt |
| Shizuku API / provider 13.1.5 | [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE)，MIT | Shizuku-API.txt |
| Ktor server CIO / core JVM 3.3.3 | [ktorio/ktor](https://github.com/ktorio/ktor/blob/main/LICENSE)，Apache-2.0 | AndroidX.txt |
| OkHttp 5.5.0 | [发布 POM 指向的上游](https://github.com/lysine-dev/okhttp/blob/main/LICENSE.txt)，Apache-2.0；原 square/okhttp 地址重定向到该上游 | AndroidX.txt |
| tomlj 1.1.1 | [tomlj/tomlj](https://github.com/tomlj/tomlj/blob/main/LICENSE)，Apache-2.0 | AndroidX.txt |

已确认随 libadb 引入并实际打包的传递组件：spake2-android 2.2.1，来源 [MuntashirAkon/spake2-java 固定源码](https://github.com/MuntashirAkon/spake2-java/tree/7615ddd680b990e14513ebb66eac4cb0dbf82464)，LGPL-3.0；APK 含 Java 部分和各打包 ABI 的 libspake2.so。libadb 的 Apache 选项不覆盖此独立依赖。保留 spake2-LGPL-3.0.txt、GPL-3.0.txt 与 spake2-SOURCE.txt；固定 C 子模块、重建 AAR、显式替换传递依赖与自签路径见 [RELINKING.md](docs/RELINKING.md)。每次二进制发行需同时提供对应应用与库源码及重建资料；入口解析验证不等于修改版已重建或安装通过。

本应用既有 PolyForm Noncommercial 1.0.0、ETA Required Notice、Stellar MPL-2.0 及其他原声明均保留；本表不改变上游或应用许可证，也不代替发布者取得额外授权。

## 随包字体

| 打包资源 | 字体与主要来源 | 通知 |
| --- | --- | --- |
| yao_title.ttf | [ZCOOL XiaoWei](https://github.com/googlefonts/zcool-xiaowei)，SIL OFL 1.1 | fonts-OFL.txt，保留 2018 版权声明 |
| yao_num_medium.ttf、yao_num_bold.ttf | Shealive Numerals Medium / Bold，派生自 [Quicksand](https://github.com/andrew-paglinawan/QuicksandFamily)，SIL OFL 1.1 | fonts-OFL.txt 保留上游 2011 / 字体内 2019 版权及 Reserved Font Name；子集已改内部识别名以避开 RFN，字形/度量/字重不变 |
| yao_body_regular.ttf、yao_body_medium.ttf | [Resource Han Rounded](https://github.com/CyanoHao/Resource-Han-Rounded)，源自 Source Han Sans，字体用 SIL OFL 1.1 | fonts-OFL.txt 保留 Cyano Hao 2018—2022、Adobe 2014—2021 版权与 Reserved Font Name 'Source' |

字体源仓库的代码许可证不替代字体许可证。字体的子集/实例化及命名须继续遵守实际 OFL 条件；通知文件不会自动授予使用保留字体名的额外许可。
