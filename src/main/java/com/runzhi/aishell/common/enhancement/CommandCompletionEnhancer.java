/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.enhancement;

import javafx.scene.input.KeyCode;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 命令补全增强器
 * 提供终端命令补全功能
 */
public class CommandCompletionEnhancer {
    private static final Set<String> COMMON_COMMANDS = Set.of(
            "ls", "cd", "pwd", "cat", "grep", "find", "cp", "mv", "rm", "mkdir",
            "rmdir", "touch", "chmod", "chown", "ps", "top", "htop", "kill",
            "ping", "wget", "curl", "scp", "ssh", "df", "du", "free", "uname",
            "whoami", "date", "cal", "man", "history", "echo", "export", "alias",
            "clear", "less", "more", "head", "tail", "sort", "uniq", "wc", "cut",
            "paste", "join", "diff", "cmp", "comm", "tr", "sed", "awk", "tar",
            "gzip", "gunzip", "zip", "unzip", "which", "whereis", "locate", 
            "ifconfig", "netstat", "route", "nslookup", "dig", "traceroute", "telnet",
            "ftp", "sftp", "rsync", "screen", "tmux", "nano", "vim", "vi", "emacs",
            "env", "printenv", "crontab", "at", "jobs", "fg", "bg", "nohup", "nice",
            "renice", "uptime", "w", "last", "login", "logout", "exit", "su", "sudo",
            "passwd", "groupadd", "useradd", "userdel", "usermod", "groupmod", "chgrp",
            "chfn", "chsh", "mount", "umount", "fsck", "mkfs", "fdisk", "parted",
            "lsblk", "blkid", "lsof", "fuser", "killall", "pkill", "pgrep", "pidof",
            "git", "docker", "kubectl", "npm", "node", "python", "pip", "make", "gcc"
    );

    public void enableCommandCompletion(InlineCssTextArea outputArea) {
        // 实现命令补全功能：addEventFilter 避免被后注册的模块覆盖（2026-10-23 审计 P1-14）
        outputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB) {
                handleTabCompletion(outputArea);
                event.consume();
            }
        });
    }

    private void handleTabCompletion(InlineCssTextArea outputArea) {
        String currentLine = getCurrentLineText(outputArea);
        if (currentLine != null && !currentLine.trim().isEmpty()) {
            // 检查当前光标前的部分是否是路径
            int caretPos = outputArea.getCaretPosition();
            String lineBeforeCaret = currentLine.substring(0, Math.min(caretPos, currentLine.length()));

            // 检查是否在路径中（包含/或~）
            if (lineBeforeCaret.contains("/") || lineBeforeCaret.contains("~")) {
                // 执行路径补全
                String completedPath = autoCompletePath(lineBeforeCaret);
                if (completedPath != null && !completedPath.equals(lineBeforeCaret)) {
                    // 替换当前行内容
                    String fullText = outputArea.getText();
                    if (fullText != null) {
                        int lineStart = findLineStart(fullText, caretPos);
                        int lineEnd = findLineEnd(fullText, caretPos);

                        String beforeLine = fullText.substring(0, lineStart);
                        String afterLine = fullText.substring(lineEnd);

                        // 截取当前行前面部分，用补全结果替换路径部分
                        String newLine = completedPath + currentLine.substring(lineBeforeCaret.length());
                        outputArea.replaceText(beforeLine + newLine + afterLine);
                        outputArea.moveTo(lineStart + completedPath.length());
                    }
                }
            } else {
                // 执行命令补全
                String completedCommand = autoCompleteCommand(currentLine.trim());
                if (completedCommand != null && !completedCommand.equals(currentLine.trim())) {
                    // 替换当前行内容
                    String fullText = outputArea.getText();
                    if (fullText != null) {
                        int lineStart = findLineStart(fullText, caretPos);
                        int lineEnd = findLineEnd(fullText, caretPos);

                        String beforeLine = fullText.substring(0, lineStart);
                        String afterLine = fullText.substring(lineEnd);

                        outputArea.replaceText(beforeLine + completedCommand + afterLine);
                        outputArea.moveTo(lineStart + completedCommand.length());
                    }
                }
            }
        }
    }

    public String autoCompleteCommand(String commandStart) {
        // 查找匹配的命令
        String bestMatch = null;
        for (String cmd : COMMON_COMMANDS) {
            if (cmd.startsWith(commandStart)) {
                if (bestMatch == null) {
                    bestMatch = cmd;
                } else {
                    // 如果找到多个匹配项，使用最长公共前缀
                    bestMatch = findLongestCommonPrefix(bestMatch, cmd);
                }
            }
        }

        return bestMatch != null ? bestMatch : commandStart;
    }

    /**
     * 路径补全功能
     */
    public String autoCompletePath(String pathStart) {
        try {
            String expandedPath = expandPath(pathStart);
            java.io.File file = new java.io.File(expandedPath);

            String parentPath = file.getParent();
            String fileNamePart = file.getName();

            if (parentPath == null) {
                parentPath = System.getProperty("user.dir");
            }

            java.io.File parentDir = new java.io.File(parentPath);
            if (parentDir.exists() && parentDir.isDirectory()) {
                java.io.File[] files = parentDir.listFiles();
                if (files != null) {
                    List<String> candidates = new ArrayList<>();
                    for (java.io.File f : files) {
                        String name = f.getName();
                        if (name.startsWith(fileNamePart)) {
                            candidates.add(f.getAbsolutePath());
                        }
                    }

                    if (candidates.size() == 1) {
                        // 只有一个匹配项，返回完整路径
                        String fullPath = candidates.get(0);
                        return convertToRelativePath(fullPath, pathStart);
                    } else if (candidates.size() > 1) {
                        // 多个匹配项，返回最长公共前缀
                        String commonPrefix = findLongestCommonPathPrefix(candidates);
                        return convertToRelativePath(commonPrefix, pathStart);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略异常，返回原始路径
        }

        return pathStart; // 如果无法补全，返回原路径
    }

    /**
     * 展开路径中的波浪号
     */
    private String expandPath(String path) {
        if (path.startsWith("~/")) {
            String homeDir = System.getProperty("user.home");
            return homeDir + path.substring(1);
        }
        return path;
    }

    /**
     * 将绝对路径转换为相对路径形式
     */
    private String convertToRelativePath(String absolutePath, String originalPath) {
        // 如果原始路径以~/开头，尝试保持这种形式
        if (originalPath.startsWith("~/")) {
            String homeDir = System.getProperty("user.home");
            if (absolutePath.startsWith(homeDir)) {
                return "~" + absolutePath.substring(homeDir.length());
            }
        }

        // 否则返回绝对路径
        return absolutePath;
    }

    /**
     * 查找多个路径的最长公共前缀
     */
    private String findLongestCommonPathPrefix(List<String> paths) {
        if (paths.isEmpty()) return "";
        if (paths.size() == 1) return paths.get(0);

        String prefix = paths.get(0);
        for (int i = 1; i < paths.size(); i++) {
            prefix = findLongestCommonPrefix(prefix, paths.get(i));
            if (prefix.isEmpty()) break;
        }

        // 确保在目录名中间不截断
        int lastSeparator = prefix.lastIndexOf('/');
        if (lastSeparator > 0) {
            String partialName = prefix.substring(lastSeparator + 1);
            if (!partialName.isEmpty()) {
                // 如果公共前缀包含部分文件名，返回到上一级目录
                return prefix.substring(0, lastSeparator + 1);
            }
        }

        return prefix;
    }

    private String findLongestCommonPrefix(String str1, String str2) {
        int minLength = Math.min(str1.length(), str2.length());
        int i = 0;
        while (i < minLength && str1.charAt(i) == str2.charAt(i)) {
            i++;
        }
        return str1.substring(0, i);
    }

    private String getCurrentLineText(InlineCssTextArea textArea) {
        if (textArea == null) return null;
        int caretPos = textArea.getCaretPosition();
        String fullText = textArea.getText();
        if (fullText == null || fullText.isEmpty()) return "";

        String[] lines = fullText.split("\n", -1);
        int currentLineIndex = 0;
        int currentPos = 0;

        for (String line : lines) {
            if (line != null && caretPos <= currentPos + line.length()) {
                return line;
            }
            currentPos += line.length() + 1; // +1 for newline character
            currentLineIndex++;
        }
        return "";
    }

    private int findLineStart(String fullText, int caretPos) {
        int lineStart = fullText.lastIndexOf('\n', caretPos - 1);
        if (lineStart == -1) lineStart = 0;
        else lineStart++; // 如果没有找到换行符，从文本开头开始
        return lineStart;
    }

    private int findLineEnd(String fullText, int caretPos) {
        int lineEnd = fullText.indexOf('\n', caretPos);
        if (lineEnd == -1) lineEnd = fullText.length(); // 如果没有找到换行符，到文本结尾结束
        return lineEnd;
    }
}