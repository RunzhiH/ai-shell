# AIShell

English | [中文](README.md)

AIShell is a desktop SSH/SFTP client written in Java (JavaFX + JediTermFX + JSch).
What makes it different from most SSH clients is that the AI lives inside the terminal:
select an error message and ask about it, describe a command in plain language, and the
suggested command is pre-filled for you to review before it runs. There's also a guarded
Agent that can read the terminal and files — write operations need your approval, and
everything is written to an audit log.

Repo: [Gitee](https://gitee.com/hongrunzhi/ai-shell) · [GitHub](https://github.com/RunzhiH/ai-shell)

## Features

SSH / terminal

- Tabbed sessions, each tab is an independent connection
- Host key verification (known_hosts); first connection needs confirmation
- Automatic reconnect, keepalive, compression
- Real PTY terminal: colors, cursor, resizing, copy/paste
- Directory sync between terminal and file panel

SFTP

- One SFTP channel per tab
- Upload / download, overwrite strategy, cancel, retry on failure
- Resumable transfers (JSch native RESUME)
- Persistent transfer queue, recoverable after a crash
- Batch operations with per-file progress

Server monitoring

- CPU, memory, load, disk, network rate, processes
- No agent or plugin needed on the server — it reads what the system already exposes
- Threshold alerts (ok / warning / critical)

AI assistant

- Ask questions right in the terminal, streaming output, cancel and retry
- Natural language to command — the result is pre-filled, nothing runs until you confirm
- OpenAI-compatible APIs (DeepSeek, Mistral, Moonshot, MiniMax, Zhipu, Xiaomi Mimo),
  Claude, Gemini, ERNIE, Qwen, local Ollama
- API keys stored encrypted on your machine
- Commands are graded by risk; high-risk ones ask for a second confirmation

Guarded Agent

- Read-only tools from a whitelist, pinned to one target tab
- Every write operation needs approval; batch jobs spell out their impact per host
- Audit log is masked automatically

Connectivity

- HTTP / SOCKS4 / SOCKS5 proxies, ProxyJump bastions
- Local / remote port forwarding, dynamic SOCKS5
- Connection import/export — passwords are never written to the export file

Other

- Light / dark theme
- Shortcut commands and parameterized command templates
- Chinese / English UI

## What's not there

No remote desktop (RDP/VNC), no ZMODEM file transfer, no Ping latency monitor, no process
manager, no built-in editor, no bulk server management. The plugin-marketplace and
cloud-sync screens still exist but are disabled by default and don't talk to any online
service.

## Requirements

JDK 21 or newer (the code uses preview features). Developed and tested on Windows;
macOS and Linux should work in theory but haven't been verified.

## Download

See [Gitee Releases](https://gitee.com/hongrunzhi/ai-shell/releases) or
[GitHub Releases](https://github.com/RunzhiH/ai-shell/releases) for Windows installers
(exe / MSI) and a portable zip.

## Building from source

    ./mvnw clean verify      # compile, test, package
    ./mvnw javafx:run        # launch the app

To build a Windows client package:

    powershell -ExecutionPolicy Bypass -File packaging/build-package.ps1

Building an MSI needs WiX 3.11, which isn't bundled. Grab wix311-binaries.zip from
https://github.com/wixtoolset/wix3/releases and unzip it into packaging/wix311. Without
WiX you still get the exe and the portable zip.

## Configuring AI

AI features need a model endpoint, a model name and an API key that you provide — or a
local Ollama. There is no hosted model service, and your usage costs are yours.
Everything else (SSH, SFTP, monitoring, command shortcuts) works without any AI
configuration.

## Third-party dependencies

The main ones: JavaFX (GPL v2 + Classpath Exception), JediTermFX (terminal),
JSch (mwiede fork, SSH), Gson, ControlsFX, TilesFX, FormsFX, RichTextFX, Logback.
Check each project for its own license and distribution terms.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Reporting issues

Bugs and feature requests go to [Gitee Issues](https://gitee.com/hongrunzhi/ai-shell/issues)
or [GitHub Issues](https://github.com/RunzhiH/ai-shell/issues).
**Don't paste server passwords, private keys, API keys or unmasked logs into an issue** —
they're public the moment you submit. For security problems see [SECURITY.md](SECURITY.md).

## Supporting the project

The project is free and open source. If it saved you some time, feel free to scan a code
below. Not donating is fine too — you get every feature either way.

| WeChat | Alipay |
| :---: | :---: |
| <img src="assets/donations/wechat-pay.jpg" width="260"> | <img src="assets/donations/alipay.jpg" width="260"> |

## License

GPL v3.0 — see [LICENSE](LICENSE). Short version: use it, modify it, build commercial
services on it. But if you distribute this app or a derivative of it, you have to publish
the corresponding source under the same license and keep the license text.
