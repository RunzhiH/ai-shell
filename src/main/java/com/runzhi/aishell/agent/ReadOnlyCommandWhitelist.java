/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict allow-list that identifies shell commands which are safe to execute without
 * confirmation. Used by the auto-approve path so that {@code /auto on} can never
 * fast-walk an unrecognised state-changing command just because the risk classifier
 * happens to rate it LOW. Also provides the safe-write subset used for low/medium-risk
 * write operations (mkdir/cp/mv/ln/install/sed -i and simple echo/printf redirection).
 * <p>
 * A command is considered read-only only when every pipeline segment is a single allow-listed
 * binary (with optional leading environment assignment such as {@code LC_ALL=C}) and the command
 * contains no shell metacharacters that could smuggle in side effects.
 */
public final class ReadOnlyCommandWhitelist {
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "ls", "pwd", "echo", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "wc",
            "stat", "file", "df", "du", "free", "vmstat", "uptime", "ps", "ss", "netstat",
            "hostname", "uname", "id", "date", "cal", "printenv", "getent", "lsblk",
            "lscpu", "lsof", "who", "w", "last", "dig", "nslookup", "systemctl", "journalctl");
    /** Only status-query subcommands of systemctl may be auto-approved. */
    private static final Set<String> SYSTEMCTL_READ_ONLY_SUBCOMMANDS = Set.of(
            "status", "is-active", "is-enabled", "list-units", "list-unit-files", "show");
    /** Anything that could chain another command or redirect output is not auto-approvable. */
    private static final Pattern FORBIDDEN_METACHARACTERS = Pattern.compile("[;&<>`]|\\$\\(|\\$\\{|\\r|\\n");
    /** -f / --follow make tailing commands long-lived; they must not be auto-approved. */
    private static final Pattern FOLLOW_FLAG = Pattern.compile("(?i)(?:^|\\s)-[a-z]*f\\b|--follow\\b");
    /** 凭据与系统配置文件即使只读也不自动批准，避免私钥/密码进入日志或对话记录。 */
    private static final Pattern SENSITIVE_PATH = Pattern.compile("(?is)" + AgentSandboxPolicy.SENSITIVE_NAMES);
    /** hostname 只允许查询类参数；任何位置参数都会修改主机名。 */
    private static final Set<String> HOSTNAME_QUERY_OPTIONS = Set.of(
            "-f", "--fqdn", "-s", "--short", "-d", "--domain", "-i", "--ip-address",
            "-I", "--all-ip-addresses", "-a", "--alias", "-A", "--all-fqdns");
    /**
     * 可自动批准的文件写入命令：只包含明确的写文件/建目录/改名工具。
     * 删除（rm/rmdir）、权限修改（chmod/chown）、服务/进程/包管理、sudo 等即使风险
     * 等级不高也不在此列，仍走人工审批。
     */
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "mkdir", "touch", "cp", "mv", "ln", "install", "sed");
    /** 简单的 echo/printf 重定向写入；禁止命令替换、额外重定向与链式操作。 */
    private static final Pattern SIMPLE_REDIRECT = Pattern.compile(
            "^(?:echo|printf)\\s+[^\\r\\n;&|<>\\x60$]*>{1,2}\\s*[^\\r\\n;&|<>\\x60$\\s]+$");

    private ReadOnlyCommandWhitelist() { }

    public static boolean isReadOnly(String command) {
        if (command == null) return false;
        String trimmed = command.trim();
        if (trimmed.isEmpty() || FORBIDDEN_METACHARACTERS.matcher(trimmed).find()) return false;
        for (String segment : trimmed.split("\\|")) {
            if (!segmentIsReadOnly(segment.trim())) return false;
        }
        return true;
    }

    /**
     * 是否是可在自动批准下执行的“安全写入”命令（中/低风险写操作）。
     * 只接受单一命令、无管道/链式操作、非敏感路径；重定向仅限简单 echo/printf，sed 必须带 -i 才是写入。
     */
    public static boolean isWriteOperation(String command) {
        if (command == null) return false;
        String trimmed = command.trim();
        if (trimmed.isEmpty() || trimmed.contains("|")) return false;
        if (SENSITIVE_PATH.matcher(trimmed).find()) return false;
        if (SIMPLE_REDIRECT.matcher(trimmed).matches()) return true;
        if (FORBIDDEN_METACHARACTERS.matcher(trimmed).find()) return false;
        String[] tokens = trimmed.split("\\s+");
        int index = 0;
        while (index < tokens.length && tokens[index].matches("[A-Za-z_][A-Za-z0-9_]*=.*")) index++;
        if (index >= tokens.length) return false;
        String name = tokens[index].toLowerCase(Locale.ROOT);
        if (!WRITE_COMMANDS.contains(name)) return false;
        return !"sed".equals(name) || hasInPlaceOption(tokens, index);
    }

    private static boolean hasInPlaceOption(String[] tokens, int commandIndex) {
        for (int index = commandIndex + 1; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.equals("--in-place") || token.startsWith("--in-place=")) return true;
            if (token.startsWith("-") && !token.startsWith("--") && token.substring(1).contains("i")) return true;
        }
        return false;
    }

    private static boolean segmentIsReadOnly(String segment) {
        if (segment.isEmpty()) return false;
        String[] tokens = segment.split("\\s+");
        int index = 0;
        // skip leading environment assignments such as LC_ALL=C
        while (index < tokens.length && tokens[index].matches("[A-Za-z_][A-Za-z0-9_]*=.*")) index++;
        if (index >= tokens.length) return false;
        String command = tokens[index].toLowerCase(Locale.ROOT);
        if (!READ_ONLY_COMMANDS.contains(command)) return false;
        // 只有会持续跟随输出的查询命令才受 -f/--follow 限制；hostname -f、grep -f 等是普通参数。
        if (("tail".equals(command) || "journalctl".equals(command)) && FOLLOW_FLAG.matcher(segment).find()) return false;
        if (SENSITIVE_PATH.matcher(segment).find()) return false;
        if ("systemctl".equals(command)) {
            String subcommand = index + 1 < tokens.length ? tokens[index + 1].toLowerCase(Locale.ROOT) : "";
            if (!SYSTEMCTL_READ_ONLY_SUBCOMMANDS.contains(subcommand)) return false;
        }
        if ("date".equals(command) && hasDateSetOption(tokens, index)) return false;
        if ("hostname".equals(command) && !isHostnameQuery(tokens, index)) return false;
        if ("journalctl".equals(command) && hasJournalMutationOption(tokens, index)) return false;
        return true;
    }

    private static boolean hasDateSetOption(String[] tokens, int commandIndex) {
        for (int index = commandIndex + 1; index < tokens.length; index++) {
            String token = tokens[index].toLowerCase(Locale.ROOT);
            if (token.startsWith("-s") || token.equals("--set") || token.startsWith("--set=")) return true;
        }
        return false;
    }

    private static boolean isHostnameQuery(String[] tokens, int commandIndex) {
        for (int index = commandIndex + 1; index < tokens.length; index++) {
            // 大小写敏感：-f 是查询 FQDN，-F 会从文件设置主机名。
            String token = tokens[index];
            if (!token.startsWith("-") || !HOSTNAME_QUERY_OPTIONS.contains(token)) return false;
        }
        return true;
    }

    private static boolean hasJournalMutationOption(String[] tokens, int commandIndex) {
        for (int index = commandIndex + 1; index < tokens.length; index++) {
            String token = tokens[index].toLowerCase(Locale.ROOT);
            if (token.startsWith("--vacuum") || token.equals("--rotate") || token.equals("--flush")
                    || token.equals("--sync")) {
                return true;
            }
        }
        return false;
    }
}
