/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.enhancement;

import javafx.application.Platform;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.regex.Pattern;

/**
 * 语法高亮增强器
 * 提供终端输出语法高亮功能
 */
public class SyntaxHighlightingEnhancer {
    private static final Pattern COMMAND_PATTERN = Pattern.compile("\\b(?:ls|cd|pwd|cat|grep|find|cp|mv|rm|mkdir|rmdir|touch|chmod|chown|ps|top|htop|kill|ping|wget|curl|scp|ssh|df|du|free|uname|whoami|date|cal|man|history|echo|export|alias|clear|less|more|head|tail|sort|uniq|wc|cut|paste|join|diff|cmp|comm|tr|sed|awk|tar|gzip|gunzip|zip|unzip|which|whereis|locate|ifconfig|netstat|route|nslookup|dig|traceroute|telnet|ftp|sftp|rsync|screen|tmux|nano|vim|vi|emacs|env|printenv|crontab|at|jobs|fg|bg|nohup|nice|renice|uptime|w|last|login|logout|exit|su|sudo|passwd|groupadd|useradd|userdel|usermod|groupmod|chgrp|chfn|chsh|mount|umount|fsck|mkfs|fdisk|parted|lsblk|blkid|lsof|fuser|killall|pkill|pgrep|pidof|git|docker|kubectl|npm|node|python|pip|make|gcc)\\b");
    private static final Pattern OPTION_PATTERN = Pattern.compile("(-[a-zA-Z0-9]+|--[a-zA-Z0-9-]+)");
    private static final Pattern PATH_PATTERN = Pattern.compile("(/[^\\s\\n]+)");
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("('[^']*'|\"[^\"]*\")");

    /** 超过此长度的输出不再做全文着色（2026-10-23 审计 P1-17）。 */
    private static final int HIGHLIGHT_LIMIT = 200_000;
    /** 着色防抖间隔：每次文本变化都立即全文重扫描会让 FX 线程随输出累积逐步卡死。 */
    private static final long THROTTLE_MS = 150;
    private final java.util.Map<InlineCssTextArea, javafx.animation.PauseTransition> throttles = new java.util.IdentityHashMap<>();

    public void enableSyntaxHighlighting(InlineCssTextArea outputArea) {
        outputArea.textProperty().addListener((obs, oldText, newText) -> scheduleHighlight(outputArea));
    }

    private void scheduleHighlight(InlineCssTextArea outputArea) {
        javafx.animation.PauseTransition pause = throttles.get(outputArea);
        if (pause == null) {
            pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(THROTTLE_MS));
            throttles.put(outputArea, pause);
        }
        pause.setOnFinished(event -> Platform.runLater(() -> applySyntaxHighlighting(outputArea)));
        pause.playFromStart();
    }

    public void applySyntaxHighlighting(InlineCssTextArea outputArea) {
        String text = outputArea.getText();
        if (text == null || text.isEmpty()) return;
        // 超长输出跳过：单次着色是 5 次 O(n) 全文扫描累积，无上限时 FX 线程会逐步卡死（2026-10-23 审计 P1-17）
        if (text.length() > HIGHLIGHT_LIMIT) return;

        // 清除现有的样式
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
        spansBuilder.add("", text.length());
        outputArea.setStyleSpans(0, spansBuilder.create());

        // 应用语法高亮
        applyPatternStyle(outputArea, COMMAND_PATTERN, "-fx-fill: #569CD6;"); // 蓝色
        applyPatternStyle(outputArea, OPTION_PATTERN, "-fx-fill: #DCDCAA;"); // 浅黄色
        applyPatternStyle(outputArea, PATH_PATTERN, "-fx-fill: #CE9178;"); // 橙色
        applyPatternStyle(outputArea, STRING_LITERAL_PATTERN, "-fx-fill: #CE9178;"); // 橙色
    }

    private void applyPatternStyle(InlineCssTextArea outputArea, Pattern pattern, String style) {
        String text = outputArea.getText();
        java.util.regex.Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            outputArea.setStyle(start, end, style);
        }
    }
}
