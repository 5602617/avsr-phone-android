# Contributor and agent guidance

This repository is the **shealive Android phone client**, paired with the [AstrBot phone plugin](https://github.com/5602617/astrbot_plugin_avsr_phone). Start with [CONTRIBUTING](CONTRIBUTING.md), [BUILD](docs/BUILD.md) and the [documentation index](docs/README.md). This is an existing Git repository; inspect current changes before editing and preserve unrelated work.

## Source layout

Kotlin sources are under `app/src/main/kotlin/io/github/lejw0925/virtualscreen/`:

- `app/` and `phone/`: application state, persona pictures and host connection/interaction.
- `ui/`: Compose onboarding, chat, settings, floating panel, preview and human handoff.
- `core/`, `backend/`, `mcp/`, `tunnel/`: device sessions, privileged execution, MCP and networking.

Resources are in `app/src/main/res/`; JVM and device tests are in `app/src/test/` and `app/src/androidTest/`. `device-fixture/` is a disposable test app. `scripts/` contains native-build and diagnostic tools. The retained Kotlin namespace is an upstream source identity; the release application ID is `dev.avsr.phone`. Do not rename either as a branding cleanup.

## Changes and verification

Follow existing Kotlin style and package responsibilities. Update English, Simplified Chinese and Traditional Chinese UI together. Keep the README's user instructions aligned with actual UI labels.

Use JDK 25 and the Android SDK versions specified in BUILD. Prepare native inputs through that guide before treating an APK as complete. Typical commands are `bash ./gradlew :app:assembleDebug`, `bash ./gradlew :app:testDebugUnitTest` and `bash ./gradlew :app:lintDebug` (`gradlew.bat` on Windows). Select checks relevant to the change and report what ran, including device/backend and any untested scope. Documentation-only changes need link and content checks, not device operations. Do not install apps, change device settings or operate connected services as an incidental check.

The plugin's [docs/protocol](https://github.com/5602617/astrbot_plugin_avsr_phone/tree/main/docs/protocol) owns the client–host contract. Coordinate protocol changes with that repository; retain feature negotiation and explicit phone consent. A negotiated capability is not permission for an action. Preserve display scoping, control leases, cancellation, handoff and phone approval paths. Main-screen operations must follow the existing consent flow.

## Contributions and sensitive material

Keep commits focused and describe user-visible behavior, relevant checks and limitations. Preserve existing staged changes and unrelated edits. Do not commit keystores, credentials, pairing material, SDK paths, databases, user pictures/chats, unreviewed screenshots or runtime logs. Release signing uses the `AVS_*` environment variables documented in BUILD.

Preserve LICENSE, third-party notices and provenance. Native `libfrpc.so` and `libcloudflared.so` are executable programs; keep the required names and packaging behavior. See [UPSTREAM](docs/UPSTREAM.md) before changing inherited code or attribution. Refer to [SECURITY](SECURITY.md) for vulnerability reporting.
