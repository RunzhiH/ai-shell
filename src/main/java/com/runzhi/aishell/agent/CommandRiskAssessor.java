/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conservative first-pass risk classifier for AI-proposed shell commands.
 * Drives the approval UX (label, colour, and whether a high-risk step needs a second
 * confirmation) as well as the low-risk auto-approve gate.
 */
public final class CommandRiskAssessor {
    /** 信任文件与凭据文件：任何写入或覆盖都按高风险处理（2026-10-23 审计 P0-2-5）。 */
    private static final String SENSITIVE_WRITE =
            "(?:authorized_keys|known_hosts|sshd_config|id_rsa|id_ecdsa|id_ed25519|id_dsa"
                    + "|/etc/passwd|/etc/shadow|/etc/sudoers|/etc/ssh/|/.ssh/|.ssh/|/boot/)";

    private static final Pattern HIGH = Pattern.compile(
            "(?is)(\\brm\\b(?:[^\\n;&|]*\\s)?(-{1,2}[a-zA-Z]*[rRfF][a-zA-Z]*)(?=\\s|$)" // rm with recursive or force flags, anywhere in the argument list
            + "|\\brm\\s+[^\\n;&|]*(?:/etc/passwd|/etc/shadow|/etc/sudoers|/etc/ssh|/boot|/usr|/bin|/sbin|/lib)\\b"
            + "|\\bfind\\s+[^\\n]*-delete\\b"
            + "|\\brm\\s+[^\n;&|]*(?:authorized_keys|known_hosts|id_rsa|\\.ssh)\\b"
            + "|\\bfind\\b[^\n]*\\s-(?:exec|execdir|ok|okdir)\\b"
            + "|\\bxargs\\b[^\n;&|]*\\b(?:rm|dd|mkfs|shred)\\b"
            + "|\\b(?:cp|mv|install|rsync)\\b[^\\n;|]*" + SENSITIVE_WRITE
            + "|\\bsed\\s+-i\\b[^\\n;|]*" + SENSITIVE_WRITE
            + "|(?:>|>>)\\s*[^\\n;|]*(?:authorized_keys|known_hosts|sshd_config|/etc/passwd|/etc/shadow|/etc/sudoers)\\b"
            + "|\\bmkfs(?:\\.|\\s)|\\bdd\\s+.*\\bof=/dev/"
            + "|\\b(?:shutdown|reboot|poweroff|halt|init\\s+0)\\b"
            + "|\\bchmod\\s+-R\\s+777\\b|:\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:"
            + "|\\biptables\\s+(?:-[FX]|--flush)\\b|\\buserdel\\b|\\bgroupdel\\b)");
    private static final Pattern MEDIUM = Pattern.compile(
            "(?is)(^|[;&|]\\s*)sudo\\b|\\b(?:kill|pkill|killall)\\b|"
            + "\\brm\\b" // any deletion that is not flagged recursive/force is still state-changing
            + "|\\b(?:apt|apt-get|dnf|yum|pacman|zypper)\\s+(?:install|remove|purge|upgrade)\\b|"
            + "\\bsystemctl\\s+(?:start|stop|enable|disable|restart)\\b|"
            + "\\bservice\\s+\\S+\\s+(?:start|stop|restart|reload|enable|disable)\\b|"
            + "(?:>|>>)\\s*/(?:etc|boot|usr)/|"
            + "(?:>|>>)\\s*[^\\n;|]*(?:/\\.ssh/|\\.ssh/)|"
            + "\\b(?:curl|wget)\\b[^\\n]*\\|\\s*(?:sh|bash|zsh)\\b");

    private CommandRiskAssessor() {}

    public static CommandRisk assess(String command) {
        if (command == null || command.isBlank()) return CommandRisk.LOW;
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (HIGH.matcher(normalized).find()) return CommandRisk.HIGH;
        if (MEDIUM.matcher(normalized).find()) return CommandRisk.MEDIUM;
        return CommandRisk.LOW;
    }
}
