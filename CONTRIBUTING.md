# 贡献指南

感谢你对 AIShell 的兴趣！本文说明如何参与开发、提交问题和代码贡献。

## 开发环境

- JDK 21（项目使用 Java 21，含预览特性）
- 不需要单独装 Maven，仓库自带 `mvnw` / `mvnw.cmd` 包装器

## 构建与运行

```bash
./mvnw clean verify     # 编译、测试、打包、覆盖率报告
./mvnw javafx:run       # 本地启动应用
```

- 构建产物：`target/AIShell-1.0.0.jar`
- 覆盖率报告：`target/site/jacoco/index.html`
- 打包 Windows 客户端（exe / 便携包 / MSI）：`packaging/build-package.ps1`。
  脚本会自动找 JAVA_HOME 或 PATH 里的 JDK；MSI 还需要 WiX 3.11，
  去 https://github.com/wixtoolset/wix3/releases 下载 wix311-binaries.zip 解压到 `packaging/wix311`

## 分支模型

| 分支 | 角色 |
| --- | --- |
| `master` | 稳定版本分支，受保护，禁止直接推送，仅通过审查通过的 PR 合并，对应发布版本 |
| `dev` | 开发分支，日常开发在此集成，合并需代码审查 |
| `feature/xxx` / `fix/xxx` / `release/xxx` | 功能 / 修复 / 发布分支，从 `dev` 拉出 |

## 提交流程

1. Fork 本仓库
2. 从 `dev` 创建分支：`git checkout -b feature/your-feature`
3. 提交代码，建议使用约定式提交信息（`feat:` / `fix:` / `docs:` / `chore:`）
4. 向 `dev` 发起 Pull Request，等待审查（至少 1 人）并确认 CI 通过
5. 审查通过后合并到 `dev`；`dev` 稳定后由维护者通过 PR 合并到 `master` 并打 tag 发版

## 代码规范

- 每个 Java 源文件保留版权头与 `SPDX-License-Identifier: GPL-3.0-only`
- 新增界面优先复用现有 FXML 与控制器模式
- 提交前运行 `./mvnw verify`，保证编译与测试通过

## 提交问题（Issue）

Issue 模板在 `.github/ISSUE_TEMPLATE/`，按提示填写。为快速定位，请尽量提供以下**脱敏后**的信息：

- 操作系统与 Java 版本
- 复现步骤
- 错误日志（`aishell.log` 或用户目录下的日志）
- SSH/SFTP 问题：主机系统类型、连接方式（密码/密钥/代理）

**不要在 Issue 中包含服务器密码、私钥、API Key、真实主机地址或未脱敏的日志**——公开后人人可见，泄露后需立即轮换。

## 许可

本项目采用 [GPL v3.0](LICENSE) 授权。你提交的代码将按同一许可证授权。
