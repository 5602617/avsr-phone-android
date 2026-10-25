# shealive Android development

Read [AGENTS.md](AGENTS.md) for repository guidance and [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow. They apply to work in this existing Git repository.

This is the shealive Android phone client: chat, voice, persona pictures, floating UI and phone-side control/consent. It connects to the separate [AstrBot phone plugin](https://github.com/5602617/astrbot_plugin_avsr_phone). The inherited virtual-screen and MCP layers remain part of the device implementation; they do not describe the entire current product.

Use [docs/README.md](docs/README.md) to locate current user, build, interface and source documentation. Build commands and native prerequisites have one maintained home in [docs/BUILD.md](docs/BUILD.md). The plugin owns [the phone protocol](https://github.com/5602617/astrbot_plugin_avsr_phone/tree/main/docs/protocol).

Preserve explicit device consent, display scoping, handoff and cancellation. Keep private device data and signing material out of changes. Describe verification honestly and preserve upstream notices. Historical validation documents describe their original version and environment, not current acceptance.
