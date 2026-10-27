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
 * confirmation. Used by the low-risk auto-approve path so that {@code /auto on} can never
 * fast-walk a state-changing command that the risk classifier happens to rate as LOW
 * (for example {@code find / -delete} or {@code service nginx stop}).
 * <p>
 * A command is considered read-only only when every pipeline segment is a single allow-listed
 * binary (with optional leading environment assignment such as {@code LC_ALL=C}) and the command
 * contains no shell metacharacters that could smuggle in side effects.
 */
public final class ReadOnlyCommandWhitelist {
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "ls", "pwd", "echo", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "wc",
            "stat", "file", "df", "du", "free", "vmstat", "uptime", "ps", "ss", "netstat",
            "hostname", "uname", "id", "date", "cal", "env", "printenv", "getent", "lsblk",
            "lscpu", "lsof", "who", "w", "last", "dig", "nslookup", "systemctl", "journalctl");
    /** Only status-query subcommands of systemctl may be auto-approved. */
    private static final Set<String> SYSTEMCTL_READ_ONLY_SUBCOMMANDS = Set.of(
            "status", "is-active", "is-enabled", "list-units", "list-unit-files", "show");
    /** Anything that could chain another command or redirect output is not auto-approvable. */
    private static final Pattern FORBIDDEN_METACHARACTERS = Pattern.compile("[;&<>`]|\\$\\(|\\$\\{|\\r|\\n");
    /** -f / --follow make tailing commands long-lived; they must not be auto-approved. */
    private static final Pattern FOLLOW_FLAG = Pattern.compile("(?i)(?:^|\\s)-[a-z]*f\\b|--follow\\b");

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

    private static boolean segmentIsReadOnly(String segment) {
        if (segment.isEmpty()) return false;
        String[] tokens = segment.split("\\s+");
        int index = 0;
        // skip leading environment assignments such as LC_ALL=C
        while (index < tokens.length && tokens[index].matches("[A-Za-z_][A-Za-z0-9_]*=.*")) index++;
        if (index >= tokens.length) return false;
        if (FOLLOW_FLAG.matcher(segment).find()) return false;
        String command = tokens[index].toLowerCase(Locale.ROOT);
        if (!READ_ONLY_COMMANDS.contains(command)) return false;
        if ("systemctl".equals(command)) {
            String subcommand = index + 1 < tokens.length ? tokens[index + 1].toLowerCase(Locale.ROOT) : "";
            if (!SYSTEMCTL_READ_ONLY_SUBCOMMANDS.contains(subcommand)) return false;
        }
        return true;
    }
}
