# shealive · Phone client

[简体中文](README.md)

The shealive Android client brings chat, voice and a floating companion to your phone. Connect it to the [AstrBot phone plugin](https://github.com/5602617/astrbot_plugin_avsr_phone) to talk with her, choose her pictures, watch phone operations and take over when she needs your help.

- **Chat and voice:** text, recordings, speech playback and a floating orb outside the app.
- **Her faces:** per-persona picture slots that respond to reply labels from the host.
- **Her screen:** watch background work and choose when she may use your main screen, hand over control or stop.
- **Cards and waits:** provide missing input, choices or approval through interaction cards. Waiter returns the answer to the host and resumes the original call.

This repository maintains the Android client. The host plugin manages accounts; administrators must configure the chat model, STT (speech recognition) and TTS (speech synthesis) on the host. Personas, persistent memory and proactive behavior come from the host. Installing the plugin on native AstrBot does not add AVSR persistent Task, memory or proactive Wake services; the client does not implement these services itself.

## Downloads and version

For the first public release, **0.1.0**, get the signed APK, SHA256 checksums, signing certificate and release notes from the [v0.1.0 release page](https://github.com/5602617/avsr-phone-android/releases/tag/v0.1.0), or [download the APK directly](https://github.com/5602617/avsr-phone-android/releases/download/v0.1.0/shealive-0.1.0.apk). The source matching the current APK is [shealive-0.1.0-source.tar.gz](https://github.com/5602617/avsr-phone-android/releases/download/v0.1.0/shealive-0.1.0-source.tar.gz); its full commit ID is recorded in the release page body. The retained `v0.1.0` tag and GitHub's automatically generated source downloads still describe the initial release and do not include the 2026-10-26 fix published under the same version.

[0.1.0 release notes](docs/RELEASE_NOTES_0.1.0.md) · [Changelog](CHANGELOG.md) · [Documentation](docs/README.md)

## Quick start

You need Android 13 or later and an enabled shealive host plugin. Ask the host administrator for the connection address and phone login account. If you run your own host, complete the [plugin quick start](https://github.com/5602617/astrbot_plugin_avsr_phone#readme) first.

1. **Prepare the client:** download the signed APK from the [v0.1.0 release page](https://github.com/5602617/avsr-phone-android/releases/tag/v0.1.0) and verify it against `SHA256SUMS` there before installing. Android may ask you to allow your browser or file manager to install unknown apps. For a Debug development APK, ask the administrator to set the plugin’s `apk_package` to `dev.avsr.phone.debug`, save and reload the plugin. Officially signed or self-signed Release APKs keep the default `dev.avsr.phone`. This matches the installed package; it does not change the application ID. See the update guidance below.
2. **Sign in:** enter **Host address, Account and Password** in “Let's meet.” For example, a host on your local network might use `http://192.168.1.20:12420`; replace this with your host's address. The phone account is configured in the host plugin, separately from the AstrBot dashboard account.
3. **Open chat:** select **“Chat first, finish setup later.”** Device-control pairing is not required for your first text conversation.
4. **Receive a reply:** type “Hi” in “Tell her what to do” and send it. Once you receive a reply, you can keep chatting or enable voice, the floating panel and phone controls below.

If the connection fails, check the IP address and port. Use the plugin's phone connection port (default `12420`), not the AstrBot dashboard port. `localhost` and `127.0.0.1` point to the phone itself, not another computer. Keep the explicit `http://` for the local-network example; omitting the scheme defaults to HTTPS. Public hosts use HTTPS.

## Enable phone controls

1. **First startup happens on the phone**, with no computer, USB or separate Shizuku app. Connect to Wi-Fi and follow **“Let her use the phone → Start pairing”** to open Developer options. Enable Wireless debugging, allow the current network, then choose Pair device with pairing code. Keep the system dialog open and enter its six-digit code through this app's notification. Pairing automatically connects wireless ADB and starts the built-in service. Return to the app; **“Built-in privileged service running”** confirms startup. No extra command is needed. If already paired but not running, select **“Restore paired service”** and follow any error shown.
2. In **“A few more permissions”**, review the system settings for **Accessibility, Floating panel, Microphone and Notifications**. Default assistant is optional; unrestricted battery use is suggested. You can inspect phone access and restore the paired service later under Settings.
3. Choose how she may use your screen and tap **“Done, let's start.”** Later, **“Settings → Your screen”** offers **“Ask first / Allowed.”** Device and background behavior varies by Android system.

## Chat, voice and floating panel

- **Chat:** type in “Tell her what to do” and send. The adjustment icon on the left opens Settings. The header shows connection status and the current persona's name.
- **Voice:** tap “Talk,” then send when finished, or cancel the recording. Voice replies can be stopped and replayed. Recognition and speech require available providers configured on the host.
- **Reply preference:** under “How she replies,” choose “Her call / Voice / Text.” “Hands-free” can listen and send when you pause; you decide whether to enable it.
- **Floating panel:** enable “Show her when you leave the app,” or use “Fold into the orb” from chat. Tap to type, double-tap to return to the app, or hold to talk. Android overlay permission is required.

## Her faces

Expand **“Settings → Her faces”** to configure each connected Persona's plain picture, named picture slots and label assignments. Labels come from the host catalog. Unassigned or unknown labels use the plain picture. Both the whole section and each slot's labels can collapse.

“Remove this persona” asks for confirmation and removes that persona's local face panel, slots, label assignments and related settings. Original image files, other personas, chats and tasks remain. The connected persona keeps a blank settings entry. A default mark appears without a configured picture; imported user images are not release assets.

## Background work, her screen and handoff

“Background” means she operates in her own screen instead of occupying the main screen you are using. The eye icon in chat or **“Settings → Phone access → View the virtual screen”** opens **“Her screen.”** This preview shows progress; it does not take control.

When a step needs your input, **“This step needs you”** appears. Tap **“I'll handle it”** to enter the handoff screen, then **“Done, continue”** when finished. You can also choose “Handle later” or “End this task.” Running tasks provide a “Stop” control.

In “Ask first” mode, a request to use your main screen offers **“Just this once / Always allow / Not now.”** You can still cancel during the countdown. “Allowed” in settings and “Use main screen” beside the composer are also explicit choices. Device permission cards require their own taps; a chat message saying “allow” does not replace the card.

## Compatibility and updates

| Component | Current scope |
| --- | --- |
| Android | Android 13 or later; background behavior, Wireless debugging and app compatibility depend on the device |
| Host plugin | `astrbot_plugin_avsr_phone` 0.1.0, phone protocol v1 with capability negotiation |
| Native AstrBot | Text, voice, faces and device interactions verified with 4.29.3; disabling during an active call and abnormal disconnections remain unverified |
| AVSR host extensions | Depend on host capabilities; fresh-install combinations have not completed validation |

Voice needs host STT/TTS providers; expression labels come from the host. No character picture set is bundled. Native AstrBot interaction waits do not provide persistent task recovery across restarts.

Updates require the same application identity and a compatible signature. Use the same distribution channel. If an update fails, check the signature and version before uninstalling or clearing data, which would lose local settings. A self-signed build generally cannot replace an installation signed by someone else.

## Troubleshooting

- **Sign-in fails:** check that the phone can reach the host address and phone-plugin port, and use the plugin account rather than an AstrBot dashboard account.
- **Chat works, voice does not:** check microphone permission and host STT/TTS configuration. Text chat can be used first.
- **Chat works, controls do not:** check pairing and Accessibility under Phone access, and look for permission cards waiting for a tap.
- **Connection drops in the background:** check notifications, overlay permission and battery restrictions. System termination and vendor power management can still interrupt it.

Report ordinary problems through [Issues](https://github.com/5602617/avsr-phone-android/issues), including app version, Android/device, reproduction steps and a sanitized description. Read [SECURITY](SECURITY.md) before reporting a security issue; keep credentials, chats and private screens out of public reports.

## Contributing and development

The [documentation index](docs/README.md) separates user, developer and source information. [CONTRIBUTING](CONTRIBUTING.md) covers source layout, setup and contributions; [BUILD](docs/BUILD.md) covers toolchains, native programs and signed builds. Advanced device interfaces and networking are described in [MCP](docs/MCP.md) and [TUNNELS](docs/TUNNELS.md).

## Acknowledgements and license

This client is based on [android-virtual-screen](https://github.com/lejw0925/android-virtual-screen), with AstrBot integration, chat, voice and persona pictures. Parts of the underlying implementation originate in [ETA](https://github.com/Mangi-11/Eta). Thanks to the upstream authors and contributors. See [upstream and changes](docs/UPSTREAM.md) for the baseline.

The source is available under [PolyForm Noncommercial 1.0.0](LICENSE), permitting noncommercial use within its terms. This is a source-available, noncommercial license. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for component licenses and copyright notices.
