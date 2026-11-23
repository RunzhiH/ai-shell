# AIShell

[English](README.en.md) | 中文

AIShell 是一个桌面 SSH/SFTP 运维工具，用 Java 写的（JavaFX + JediTermFX + JSch）。
它和一般 SSH 客户端的主要区别是 AI 做在终端里：选中报错直接问、自然语言转命令、
生成的命令先预填进去，你确认了才执行。另外有个受控 Agent，能读终端和文件，
写操作要审批，全程留审计日志。

仓库：[Gitee](https://gitee.com/hongrunzhi/ai-shell) · [GitHub](https://github.com/RunzhiH/ai-shell)

## 功能

SSH / 终端

- 多标签，每个标签独立连接，互不影响
- 主机指纹校验（known_hosts），首次连接需要确认
- 断线自动重连、保活、压缩
- 真实 PTY 终端：颜色、光标、调整窗口大小、复制粘贴
- 终端和文件面板目录同步
- ZMODEM 传文件：远端 `sz` 直接收、`rz` 直接发，终端里弹出进度和取消

SFTP

- 每个标签独立 SFTP 连接
- 上传 / 下载、覆盖策略、取消、失败重试
- 打包上传：目录压成一个 zip 传完再远端解压，适合小文件多
- 断点续传（JSch 原生 RESUME）
- 传输队列持久化，异常退出后能恢复
- 批量操作，带每个文件的进度

服务器监控

- CPU、内存、负载、磁盘、网络速率、进程
- 不需要在服务器上装任何东西，读的是系统本来的信息（/proc 等）
- 阈值告警（正常 / 警告 / 严重）

AI 助手

- 终端里直接提问，流式输出，能取消和重试
- 自然语言转命令，生成后预填，确认才执行
- 支持 OpenAI 兼容接口（DeepSeek、Mistral、Moonshot、MiniMax、智谱、小米 Mimo）、
  Claude、Gemini、文心、通义、Ollama 本地模型
- API Key 本机加密存储
- 命令按风险分级，高风险的会让你二次确认

受控 Agent

- 只能调白名单里的只读工具，绑死在目标标签上
- 写操作逐个审批，批量操作会逐台主机列影响范围
- 审计日志自动脱敏
- 在 Agent 面板运行 `/skill-install "C:\My Skills\example"`，可导入本地目录中的 `SKILL.md`。运行 `/skill-install https://github.com/OWNER/REPO`，可批量导入公开仓库默认分支中的 `SKILL.md`。
- 安装的技能保存在本机。用 `/skills all` 查看完整列表，用 `/skill <名称>` 将指令填入 Agent 输入框。需要指定分类时，可在 `SKILL.md` 的 YAML 元数据里设置 `metadata.os` 为 `linux`、`macos` 或 `windows`，省略时适用于所有系统。安装范围为 Markdown 指令，技能附带的脚本和资源文件需另行处理。

内置与外部编辑器

- 文件面板双击 / 右键直接编辑远端文本文件，经 SFTP 保存，远端不需要装任何软件；右键与工具栏的「外部编辑器」是二级菜单，可随时换编辑器
- 保留原文件的编码（UTF-8 BOM / UTF-16 BOM / GBK 回退）、换行符（LF / CRLF / CR），
  保存不做全文件 diff
- 保存前按 mtime / size 做冲突检测，文件被改过时弹「重新加载 / 覆盖 / 另存为」
- 大文件、二进制、无权限文件只读保护：超大文件可只读打开、丢给外部编辑器或下载
- 外部编辑器：自动检测 VS Code、Notepad++、Sublime Text、Vim、JetBrains 全家桶（IntelliJ IDEA 等）、
  记事本、TextEdit、gedit 等；右键与编辑器窗口工具栏均为二级菜单，可随时换编辑器，
  也能「选择其他程序…」指定任一可执行文件；下载到会话级临时文件夹，监视保存后自动回传
- 本地文件也能用同一套编辑器打开

连接和网络

- HTTP / SOCKS4 / SOCKS5 代理、ProxyJump 跳板机
- 本地 / 远程端口转发、动态 SOCKS5
- 连接导入导出，导出文件不含密码

其他

- 浅色 / 深色主题切换
- 快捷命令和参数化命令模板
- 中英双语界面

## 还没有的功能

远程桌面（RDP/VNC）、Ping 延迟监控、进程管理、
批量服务器管理这些都没有，别抱期待。插件市场和云同步界面还在，但默认是关的，
也不连任何在线服务。

## 运行环境

JDK 21 及以上（用了 Java 预览特性，低版本跑不了）。主要在 Windows 上开发和测试，
macOS 和 Linux 理论上能跑，但没有实际验证过。

## 下载

[Gitee Releases](https://gitee.com/hongrunzhi/ai-shell/releases) 或
[GitHub Releases](https://github.com/RunzhiH/ai-shell/releases)，
有 Windows 安装包（exe / MSI）和免安装压缩包。

## 自己构建

    ./mvnw clean verify      # 编译、测试、打包
    ./mvnw javafx:run        # 直接启动

打 Windows 客户端包：

    powershell -ExecutionPolicy Bypass -File packaging/build-package.ps1

MSI 打包需要 WiX 3.11，脚本不自带，去 https://github.com/wixtoolset/wix3/releases
下载 wix311-binaries.zip 解压到 packaging/wix311 目录。没有 WiX 也能出 exe 和便携包。

打 macOS 客户端包（jpackage 不支持交叉打包，必须在 macOS 上运行）：

    bash packaging/build-package.sh

产物在 target/dist：AIShell.app 可以直接双击运行，AIShell-<version>.dmg
拖进 Applications 安装。默认不签名，直接分发会被 Gatekeeper 拦；正式发版用
AISHELL_MAC_SIGN_IDENTITY 环境变量传入 Developer ID 证书，并用 xcrun notarytool
公证。

## 配置 AI

AI 功能需要你自己填模型地址、模型名和 API Key，或者连本地的 Ollama。项目方不提供
模型服务，也不替你出调用费用。不配 AI 也能正常用，SSH、SFTP、监控、快捷命令都不依赖它。

## 第三方依赖

主要用的：JavaFX（GPL v2 + Classpath Exception）、JediTermFX（终端）、
JSch（mwiede 维护的分支，SSH）、Gson、ControlsFX、TilesFX、FormsFX、RichTextFX、
Logback。每个依赖的具体许可和分发要求以各自项目为准。

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 报告问题

发现 bug 或想加功能，去 [Gitee Issues](https://gitee.com/hongrunzhi/ai-shell/issues) 或
[GitHub Issues](https://github.com/RunzhiH/ai-shell/issues) 提。
**别在 Issue 里贴服务器密码、私钥、API Key 和没脱敏的日志**——公开后人人可见。
安全问题看 [SECURITY.md](SECURITY.md)。

## 支持这个项目

项目免费开源。如果你用着觉得省事了，可以扫码随意打赏，不支持也能用全部功能。

| 微信 | 支付宝 |
| :---: | :---: |
| <img src="assets/donations/wechat-pay.jpg" width="260"> | <img src="assets/donations/alipay.jpg" width="260"> |

## 许可证

GPL v3.0，见 [LICENSE](LICENSE)。简单说：随便用、随便改、也可以拿去做商业服务，
但如果你分发这个软件或它的衍生版本，得同步开源你的修改并保留许可证。
