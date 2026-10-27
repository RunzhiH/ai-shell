/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import java.util.regex.Pattern;

/**
 * Execution guardrails for commands run through the non-interactive Agent channel.
 * <p>
 * Two layers:
 * <ul>
 *   <li>{@link #INTERACTIVE} / {@link #DETACHED} / {@link #INLINE_SCRIPT} / {@link #INTERPRETER_WRAPPER}
 *       / {@link #SCRIPT_FILE} / {@link #COMMAND_SUBSTITUTION} reject command shapes that cannot be
 *       handled safely on this channel at all. Commands are required to be single-line: a newline
 *       is a legal shell command separator that would let a second command slip past every shape anchor below.</li>
 *   <li>{@link #CATASTROPHIC} / {@link #SENSITIVE_TARGET} reject irreversible system-level operations
 *       and writes to trust or configuration files that must never run even after human approval
 *       (wiping a system directory, reformatting, shutting down, flushing the firewall, truncating
 *       SSH trust files, redirecting into {@code .ssh} or {@code /etc}, piping a download into a
 *       shell, copying a downloaded key over {@code authorized_keys}, running a script file whose
 *       content never gets inspected). Scoped equivalents of the same operations remain available
 *       as gated, audited tools or approvable commands.</li>
 * </ul>
 */
public final class AgentSandboxPolicy {
    public record Verdict(boolean allowed, String reason) {
        static Verdict allow() { return new Verdict(true, ""); }
        static Verdict deny(String reason) { return new Verdict(false, reason); }
    }

    private static final int MAX_COMMAND_LENGTH = 16 * 1024;

    /**
     * 信任文件、凭据文件与系统目录的统一定义，供命令层（重定向/cp/mv/sed -i）与 SFTP 工具层共用。
     */
    static final String SENSITIVE_NAMES =
            "(?:authorized_keys|authorized_keys2|known_hosts|sshd_config|id_rsa|id_ecdsa|id_ed25519|id_dsa"
                    + "|/etc/passwd|/etc/shadow|/etc/sudoers|/etc/ssh/|/\\.ssh/|\\.ssh/"
                    + "|\\.bashrc|\\.bash_profile|\\.profile|\\.zshrc|\\.bash_history"
                    + "|/boot/|/usr/lib|/lib64|gnupg|\\.pem|\\.key)";

    /** 允许出现在交互式/解释器命令之前的修饰前缀（sudo vim、time top、xargs mysql 等）。 */
    private static final String COMMAND_PREFIX = "(?:^|[;&|]\\s*|\\b(?:sudo|time|nohup|xargs|exec|command|nice|ionice|env)\\b[^;&|]*?)";

    private static final Pattern INTERACTIVE = Pattern.compile(
            "(?is)" + COMMAND_PREFIX + "(?:vim?|nano|emacs|less|more|top|htop|watch|passwd|su|login|ssh|sftp|ftp|telnet"
                    + "|mysql|psql|redis-cli|mongo|mongosh|sqlite3|sqlplus"
                    + "|python[23]?|node|ruby|lua|perl|php|irb|jshell|scala|groovysh"
                    + "|expect|script|screen|bash|sh|zsh|dash|ksh)"
                    + "(?:\\s|$)|\\btail\\s+[^;&|]*(?:-f|--follow)\\b|(^|[;&|]\\s*)read(?:\\s|$)");
    private static final Pattern DETACHED = Pattern.compile(
            "(?is)(?:^|[;&|]\\s*)(?:nohup|screen|tmux)(?:\\s|$)|(?:^|[^&])&\\s*$|\\bdisown(?:\\s|$)");
    private static final Pattern INLINE_SCRIPT = Pattern.compile(
            "(?i)\\b(?:python[23]?|perl|ruby|node|php|awk|lua)\\s+-(?:c|e|r)\\b"
                    + "|\\bawk\\s+'[^']*system\\s*\\("
                    + "|\\bperl\\s+'[^']*exec\\s*\\("
                    + "|\\bpython[23]?\\s+-c\\s+['\"]"
                    + "|\\bnode\\s+-e\\s+['\"]");

    /**
     * Shell interpreters hide the real command from every other layer of this sandbox, so any
     * interpreter invocation with an argument — inline script ({@code bash -c '…'}) or script file
     * ({@code bash /tmp/x.sh}) — is rejected outright on the Agent channel. Bare interactive
     * invocation is rejected by {@link #INTERACTIVE}.
     */
    private static final Pattern INTERPRETER_WRAPPER = Pattern.compile(
            "(?is)" + COMMAND_PREFIX + "(?:bash|sh|zsh|dash|ksh|busybox(?:\\s+sh)?)\\s+\\S");

    /**
     * 以脚本文件为参数的执行（source/dot）与从临时目录直接运行可执行文件：脚本内容从不经过
     * 任何沙箱检查，等同绕过整个沙箱，一律拒绝。
     */
    private static final Pattern SCRIPT_FILE = Pattern.compile(
            "(?is)(?:^|[;&|]\\s*)(?:\\.|source)\\s+\\S"
                    + "|(?:^|[;&|]\\s*)(?:\\./|\\.\\./|/(?:tmp|var/tmp|dev/shm)/)\\S");

    /** Command substitution smuggles arbitrary commands past argument-level inspection. */
    private static final Pattern COMMAND_SUBSTITUTION = Pattern.compile(
            "(?is)\\$\\(|`|\\$\\{IFS[^}]*\\}|<\\(");

    /** Writes into trust, authentication, or system configuration files (including cp/mv/install/sed -i). */
    private static final Pattern SENSITIVE_TARGET = Pattern.compile(
            "(?is)(?:(?:>|>>|\\btee\\b)[^\\n;|]*" + SENSITIVE_NAMES + ")"
                    + "|(?:(?:^|[;&|]\\s*)(?:cp|mv|install|rsync)\\b[^\\n;&|]*" + SENSITIVE_NAMES + ")"
                    + "|(?:\\bsed\\s+-i\\b[^\\n;&|]*" + SENSITIVE_NAMES + ")");

    /** Irreversible, system-wide operations: no approval path exists for these on this channel. */
    private static final Pattern CATASTROPHIC = Pattern.compile(
            "(?is)\\brm\\b[^\\n;&|]*?(?:-{1,2}[a-zA-Z]*[rRfF][a-zA-Z]*|--force|--recursive)(?:\\b|\\s)[^\\n;&|]*?"
                    + "(?:/\\s*$|/(?:etc|usr|var|bin|boot|root|home|lib|lib64|sbin|proc|sys|dev)(?=[/\\s]|$)|~(?:\\s|$)|/\\*)"
                    + "|\\bfind\\s+(?:/|/(?:etc|usr|var|bin|boot|root|home|lib|sbin))\\b[^\\n]*-delete\\b"
                    + "|\\bfind\\b[^\\n]*\\s-(?:exec|execdir|ok|okdir)\\b"
                    + "|\\bxargs\\b[^\\n;&|]*\\b(?:rm|dd|mkfs|shred|chmod|chown|mv|bash|sh|zsh)\\b"
                    + "|\\bmkfs(?:\\.|\\s)|\\bdd\\s+.*\\bof=/dev/"
                    + "|\\b(?:shutdown|reboot|poweroff|halt|init\\s+0)\\b"
                    + "|\\biptables\\s+(?:-[FX]|--flush)\\b"
                    + "|\\bchmod\\s+-R\\s+777\\s+/(?:\\s|$)"
                    + "|:\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:"
                    + "|(?:>|>>|\\btee\\b)[^\\n;|]*(?:authorized_keys|known_hosts|sshd_config)"
                    + "|\\b(?:curl|wget)\\b[^\\n]*\\|\\s*(?:sh|bash|zsh)\\b");

    private AgentSandboxPolicy() { }

    public static Verdict validate(String command) {
        if (command == null || command.isBlank()) return Verdict.deny("命令为空");
        if (command.length() > MAX_COMMAND_LENGTH) return Verdict.deny("命令超过 16 KiB 限制");
        // 换行与回车都是合法 shell 命令分隔符，Agent 通道只允许单行命令
        if (command.indexOf('\0') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) {
            return Verdict.deny("命令包含非法控制字符");
        }
        if (INTERACTIVE.matcher(command).find()) return Verdict.deny("Agent 执行通道不支持交互式命令");
        if (DETACHED.matcher(command).find()) return Verdict.deny("Agent 不允许启动后台或脱离会话的进程");
        if (INLINE_SCRIPT.matcher(command).find()) return Verdict.deny("Agent 不允许通过解释器执行内联脚本");
        if (INTERPRETER_WRAPPER.matcher(command).find()) {
            return Verdict.deny("Agent 不允许通过 shell 解释器包装执行命令（bash -c、bash script.sh 等）；请直接给出命令");
        }
        if (SCRIPT_FILE.matcher(command).find()) {
            return Verdict.deny("Agent 不允许执行脚本文件（source/.、./x.sh、/tmp/x 等）；脚本内容不经过沙箱检查");
        }
        if (COMMAND_SUBSTITUTION.matcher(command).find()) {
            return Verdict.deny("Agent 命令不允许命令替换（$(…)、反引号、${IFS}、<(…)）");
        }
        if (SENSITIVE_TARGET.matcher(command).find()) {
            return Verdict.deny("Agent 命令不允许写入信任文件或系统配置（.ssh、/etc 等），无论使用重定向还是 cp/mv/sed -i");
        }
        if (CATASTROPHIC.matcher(command).find()) {
            return Verdict.deny("该命令属于不可恢复的系统级操作，禁止在 Agent 通道执行；"
                    + "请改用受审批与审计保护的删除/写文件工具");
        }
        return Verdict.allow();
    }
}
