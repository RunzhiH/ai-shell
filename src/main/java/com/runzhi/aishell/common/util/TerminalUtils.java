/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端相关工具类，包含常用的终端功能方法
 */
@Slf4j
public class TerminalUtils {

    // ANSI颜色代码正则表达式
    public static final Pattern ANSI_COLOR_PATTERN = Pattern.compile("\\u001B\\[([;\\d]*)m");

    // 预编译正则表达式以提高性能
    public static final Pattern TITLE_SEQUENCE_PATTERN = Pattern.compile("\\u001B\\][02];[^\\u0007]*\\u0007");
    public static final Pattern TITLE_SEQUENCE_ALT_PATTERN = Pattern.compile("\\u001B\\][02];[^\\u001B]*\\u001B\\\\");
    public static final Pattern OTHER_OSC_SEQUENCE_PATTERN = Pattern.compile("\\u001B\\][0-9]+;[^\\u0007]*\\u0007");
    public static final Pattern OTHER_OSC_SEQUENCE_ALT_PATTERN = Pattern.compile("\\u001B\\][0-9]+;[^\\u001B]*\\u001B\\\\");
    // 处理类似 \u001B]0;root@VM-0-4-centos:~\u001B[?1034h 的序列
    public static final Pattern OSC_WITH_MODE_SETTING_PATTERN = Pattern.compile("\\u001B\\][0-9]+;[^\\u001B]*\\u001B\\[[?0-9;]*[hlmntsuABCDEFGHJKPQRc]?");
    public static final Pattern DECSET_DECRST_PATTERN = Pattern.compile("\\u001B\\[\\?[0-9;]*[hl]");
    // 末尾字符类不含 m：SGR(ESC[...m) 是颜色码，被剥掉后 AnsiColorProcessor 永远匹配不到颜色（2026-10-23 审计 P1-12）
    public static final Pattern CURSOR_SCREEN_CONTROL_PATTERN = Pattern.compile("\\u001B\\[[0-9;]*[ABCDEFGHJKSTfnsu]");
    public static final Pattern DEVICE_QUERY_PATTERN = Pattern.compile("\\u001B\\[=[0-9;]*[c]");
    public static final Pattern BEL_PATTERN = Pattern.compile("\\u0007");
    public static final Pattern SECONDARY_DEVICE_PATTERN = Pattern.compile("\\u001B\\[>[0-9;]*[c]");
    public static final Pattern STRING_TERMINATOR_PATTERN = Pattern.compile("\\u001B\\\\");
    public static final Pattern PRIVACY_MESSAGE_PATTERN = Pattern.compile("\\u001B\\^");
    public static final Pattern APP_PROGRAM_CMD_PATTERN = Pattern.compile("\\u001B\\_");

    /**
     * 从一行文本中提取命令部分（去掉提示符等）
     */
    public static String extractCommandFromLine(String line) {
        if (line == null) return null;
        // 去掉常见的提示符前缀，如 $, #, >, %, :, ~, etc.
        Pattern promptPattern = Pattern.compile("^.*?[#$>%~:\\-]\\s*");
        Matcher matcher = promptPattern.matcher(line.trim());
        if (matcher.find()) {
            return matcher.replaceFirst("").trim();
        }
        return line.trim();
    }

    /**
     * 清理控制序列（如终端标题设置）
     *
     * @param text 包含控制序列的文本
     * @return 清理后的文本
     */
    public static String cleanControlSequences(String text) {
        if (text == null) return null;

        // 移除终端标题设置序列: \u001B]0;title\u0007 或 \u001B]0;title\u001B\
        // 这些序列用于设置终端窗口标题，不应该显示在终端输出中

        // 移除标题设置序列: ESC]0;...BEL 或 ESC]0;...ESC\
        String cleaned = TITLE_SEQUENCE_PATTERN.matcher(text).replaceAll("");  // 标题设置序列
        cleaned = TITLE_SEQUENCE_ALT_PATTERN.matcher(cleaned).replaceAll("");  // 标题设置序列

        // 移除其他窗口属性设置序列
        cleaned = OTHER_OSC_SEQUENCE_PATTERN.matcher(cleaned).replaceAll("");  // 其他OSC序列
        cleaned = OTHER_OSC_SEQUENCE_ALT_PATTERN.matcher(cleaned).replaceAll("");  // 其他OSC序列
        
        // 移除OSC序列与模式设置的组合
        cleaned = OSC_WITH_MODE_SETTING_PATTERN.matcher(cleaned).replaceAll("");  // OSC与模式设置组合序列

        // 移除终端模式设置序列: ESC[?...h/l
        cleaned = DECSET_DECRST_PATTERN.matcher(cleaned).replaceAll("");  // DECSET/DECRST序列

        // 移除光标和屏幕控制序列
        cleaned = CURSOR_SCREEN_CONTROL_PATTERN.matcher(cleaned).replaceAll("");  // 光标/屏幕控制
        cleaned = DEVICE_QUERY_PATTERN.matcher(cleaned).replaceAll("");  // 设备属性查询

        // 移除其他控制序列
        cleaned = BEL_PATTERN.matcher(cleaned).replaceAll("");  // BEL字符
        cleaned = SECONDARY_DEVICE_PATTERN.matcher(cleaned).replaceAll("");  // 次要设备识别

        // 清理可能存在的多余空格或特殊字符
        cleaned = STRING_TERMINATOR_PATTERN.matcher(cleaned).replaceAll("");  // ST (String Terminator)
        cleaned = PRIVACY_MESSAGE_PATTERN.matcher(cleaned).replaceAll("");  // PM (Privacy Message)
        cleaned = APP_PROGRAM_CMD_PATTERN.matcher(cleaned).replaceAll("");  // APC (Application Program Command)

        return cleaned;
    }

    /**
     * 显示确认对话框
     */
    public static boolean showConfirmationDialog(String title, String header, String content) {
        return Platform.isFxApplicationThread() ? 
            showConfirmationDialogSync(title, header, content) : 
            showConfirmationDialogAsync(title, header, content);
    }

    private static boolean showConfirmationDialogSync(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private static boolean showConfirmationDialogAsync(String title, String header, String content) {
        boolean[] result = new boolean[1];
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);

            Optional<ButtonType> confirmResult = alert.showAndWait();
            result[0] = confirmResult.isPresent() && confirmResult.get() == ButtonType.OK;
        });

        // 由于这是异步调用，实际上无法同步返回结果
        // 更好的方式是在需要确认的地方使用回调或CompletableFuture
        log.warn("异步线程中调用确认对话框，这可能导致UI问题。请在JavaFX应用线程中调用。");
        return false; // 返回false作为默认值
    }

    /**
     * 设置输入模式选择框的行为
     */
    public static void setupInputModeChoiceBox(ChoiceBox<String> inputModeChoiceBox, TextField commandInput) {
        if (inputModeChoiceBox != null && commandInput != null) {
            inputModeChoiceBox.setOnAction(e -> {
                String selectedMode = inputModeChoiceBox.getValue();
                if ("AI模式".equals(selectedMode)) {
                    commandInput.setPromptText("输入自然语言命令，AI将智能转换为Linux命令...");
                } else {
                    commandInput.setPromptText("输入命令...");
                }
            });
        }
    }
}