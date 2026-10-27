/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement.modules;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.HashMap;
import java.util.Map;

/**
 * 增强型键盘快捷键模块
 * 提供更多高级键盘快捷键功能
 */
public class EnhancedKeyboardShortcutModule implements TerminalEnhancementModule {
    
    private final Map<String, Boolean> enabledTabs = new HashMap<>();
    
    public EnhancedKeyboardShortcutModule() {}

    @Override
    public void initialize() {
        // 初始化增强型键盘快捷键模块
    }

    private final java.util.Map<InlineCssTextArea, javafx.event.EventHandler<javafx.scene.input.KeyEvent>> keyHandlers =
            new java.util.IdentityHashMap<>();

    @Override
    public void enable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.put(tabId, true);
        }
        
        // addEventFilter 而非 setOnKeyPressed：避免与补全/历史/AI 模块的处理器互相覆盖（2026-10-23 审计 P1-14）
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> handler =
                event -> handleEnhancedShortcuts(event, outputArea);
        keyHandlers.put(outputArea, handler);
        outputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, handler);
    }

    @Override
    public void disable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.remove(tabId);
        }
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> handler = keyHandlers.remove(outputArea);
        if (handler != null) {
            outputArea.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, handler);
        }
    }

    @Override
    public boolean isEnabled(String tabId) {
        return tabId != null && enabledTabs.containsKey(tabId);
    }

    @Override
    public String getModuleName() {
        return "enhanced-keyboard-shortcut";
    }

    @Override
    public String getDescription() {
        return "提供增强型键盘快捷键功能";
    }
    
    /**
     * 处理增强的键盘快捷键
     */
    private void handleEnhancedShortcuts(KeyEvent event, InlineCssTextArea outputArea) {
        // Ctrl+Shift+C - 复制选中文本
        if (event.getCode() == KeyCode.C && event.isControlDown() && event.isShiftDown()) {
            outputArea.copy();
            event.consume();
        }
        // Ctrl+Shift+V - 粘贴文本
        else if (event.getCode() == KeyCode.V && event.isControlDown() && event.isShiftDown()) {
            outputArea.paste();
            event.consume();
        }
        // Ctrl+Shift+K - 清空当前行
        else if (event.getCode() == KeyCode.K && event.isControlDown() && event.isShiftDown()) {
            clearCurrentLine(outputArea);
            event.consume();
        }
        // Ctrl+Shift+L - 清空整个终端
        else if (event.getCode() == KeyCode.L && event.isControlDown() && event.isShiftDown()) {
            outputArea.clear();
            outputArea.appendText("终端已清空\n");
            event.consume();
        }
        // Ctrl+Shift+U - 转换为大写
        else if (event.getCode() == KeyCode.U && event.isControlDown() && event.isShiftDown()) {
            convertSelectedTextToUpperCase(outputArea);
            event.consume();
        }
        // Ctrl+Shift+D - 转换为小写
        else if (event.getCode() == KeyCode.D && event.isControlDown() && event.isShiftDown()) {
            convertSelectedTextToLowerCase(outputArea);
            event.consume();
        }
        // Ctrl+Alt+Left - 跳转到单词开始
        else if (event.getCode() == KeyCode.LEFT && event.isControlDown() && event.isAltDown()) {
            moveToWordStart(outputArea);
            event.consume();
        }
        // Ctrl+Alt+Right - 跳转到单词结束
        else if (event.getCode() == KeyCode.RIGHT && event.isControlDown() && event.isAltDown()) {
            moveToWordEnd(outputArea);
            event.consume();
        }
        // Ctrl+Shift+Backspace - 删除当前单词
        else if (event.getCode() == KeyCode.BACK_SPACE && event.isControlDown() && event.isShiftDown()) {
            deleteCurrentWord(outputArea);
            event.consume();
        }
        // Ctrl+Shift+Delete - 删除到行尾
        else if (event.getCode() == KeyCode.DELETE && event.isControlDown() && event.isShiftDown()) {
            deleteToEndOfLine(outputArea);
            event.consume();
        }
        // Alt+Up - 向上移动一段代码
        else if (event.getCode() == KeyCode.UP && event.isAltDown() && !event.isControlDown()) {
            moveLineUp(outputArea);
            event.consume();
        }
        // Alt+Down - 向下移动一段代码
        else if (event.getCode() == KeyCode.DOWN && event.isAltDown() && !event.isControlDown()) {
            moveLineDown(outputArea);
            event.consume();
        }
        // Ctrl+Shift+Z - 重做操作
        else if (event.getCode() == KeyCode.Z && event.isControlDown() && event.isShiftDown()) {
            outputArea.redo();
            event.consume();
        }
        // Ctrl+/ - 添加/移除注释（对于命令行，这可以用来注释/取消注释命令）
        else if (event.getCode() == KeyCode.SLASH && event.isControlDown()) {
            toggleComment(outputArea);
            event.consume();
        }
        // Ctrl+Shift+/ - 插入注释块
        else if (event.getCode() == KeyCode.SLASH && event.isControlDown() && event.isShiftDown()) {
            insertCommentBlock(outputArea);
            event.consume();
        }
        // Ctrl+Shift+A - 全选
        else if (event.getCode() == KeyCode.A && event.isControlDown() && event.isShiftDown()) {
            outputArea.selectAll();
            event.consume();
        }
        // Ctrl+Shift+F - 格式化当前行
        else if (event.getCode() == KeyCode.F && event.isControlDown() && event.isShiftDown()) {
            formatCurrentLine(outputArea);
            event.consume();
        }
    }
    
    /**
     * 清空当前行
     */
    private void clearCurrentLine(InlineCssTextArea outputArea) {
        String fullText = outputArea.getText();
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        
        int caretPos = outputArea.getCaretPosition();
        int lineStart = findLineStart(fullText, caretPos);
        int lineEnd = findLineEnd(fullText, caretPos);
        
        String beforeLine = fullText.substring(0, lineStart);
        String afterLine = fullText.substring(lineEnd);
        
        outputArea.replaceText(beforeLine + afterLine);
        outputArea.moveTo(lineStart);
    }
    
    /**
     * 转换选中文本为大写
     */
    private void convertSelectedTextToUpperCase(InlineCssTextArea outputArea) {
        String selectedText = outputArea.getSelectedText();
        if (selectedText != null && !selectedText.isEmpty()) {
            String upperCaseText = selectedText.toUpperCase();
            outputArea.replaceSelection(upperCaseText);
        }
    }
    
    /**
     * 转换选中文本为小写
     */
    private void convertSelectedTextToLowerCase(InlineCssTextArea outputArea) {
        String selectedText = outputArea.getSelectedText();
        if (selectedText != null && !selectedText.isEmpty()) {
            String lowerCaseText = selectedText.toLowerCase();
            outputArea.replaceSelection(lowerCaseText);
        }
    }
    
    /**
     * 移动到单词开始
     */
    private void moveToWordStart(InlineCssTextArea outputArea) {
        int currentPos = outputArea.getCaretPosition();
        String text = outputArea.getText();
        
        if (text == null || currentPos <= 0) {
            return;
        }
        
        // 向前查找单词边界
        while (currentPos > 0 && !isWordBoundary(text.charAt(currentPos - 1))) {
            currentPos--;
        }
        
        outputArea.moveTo(currentPos);
    }
    
    /**
     * 移动到单词结束
     */
    private void moveToWordEnd(InlineCssTextArea outputArea) {
        int currentPos = outputArea.getCaretPosition();
        String text = outputArea.getText();
        
        if (text == null || currentPos >= text.length()) {
            return;
        }
        
        // 向后查找单词边界
        while (currentPos < text.length() && !isWordBoundary(text.charAt(currentPos))) {
            currentPos++;
        }
        
        outputArea.moveTo(currentPos);
    }
    
    /**
     * 检查字符是否为单词边界
     */
    private boolean isWordBoundary(char c) {
        return !Character.isLetterOrDigit(c) && c != '_' && c != '-';
    }
    
    /**
     * 删除当前单词
     */
    private void deleteCurrentWord(InlineCssTextArea outputArea) {
        int startPos = outputArea.getCaretPosition();
        String text = outputArea.getText();
        
        if (text == null || startPos <= 0) {
            return;
        }
        
        int endPos = startPos;
        
        // 向前查找单词开始
        while (startPos > 0 && !isWordBoundary(text.charAt(startPos - 1))) {
            startPos--;
        }
        
        outputArea.deleteText(startPos, endPos);
        outputArea.moveTo(startPos);
    }
    
    /**
     * 删除到行尾
     */
    private void deleteToEndOfLine(InlineCssTextArea outputArea) {
        int currentPos = outputArea.getCaretPosition();
        String text = outputArea.getText();
        
        if (text == null || currentPos >= text.length()) {
            return;
        }
        
        // 查找行尾
        int lineEnd = text.indexOf('\n', currentPos);
        if (lineEnd == -1) {
            lineEnd = text.length();
        }
        
        outputArea.deleteText(currentPos, lineEnd);
    }
    
    /**
     * 向上移动行
     */
    private void moveLineUp(InlineCssTextArea outputArea) {
        String fullText = outputArea.getText();
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        
        int caretPos = outputArea.getCaretPosition();
        int lineStart = findLineStart(fullText, caretPos);
        int lineEnd = findLineEnd(fullText, caretPos);
        
        if (lineStart == 0) {
            // 已经是第一行，无法上移
            return;
        }
        
        String currentLine = fullText.substring(lineStart, lineEnd);
        int prevLineStart = findLineStart(fullText, lineStart - 1);
        String prevLine = fullText.substring(prevLineStart, lineStart - 1); // -1 to exclude \n
        
        String beforePrevLine = fullText.substring(0, prevLineStart);
        String afterCurrentLine = fullText.substring(lineEnd);
        
        String newText = beforePrevLine + currentLine + "\n" + prevLine + afterCurrentLine;
        outputArea.replaceText(newText);
        
        // 移动光标到新位置
        outputArea.moveTo(prevLineStart + currentLine.length());
    }
    
    /**
     * 向下移动行
     */
    private void moveLineDown(InlineCssTextArea outputArea) {
        String fullText = outputArea.getText();
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        
        int caretPos = outputArea.getCaretPosition();
        int lineStart = findLineStart(fullText, caretPos);
        int lineEnd = findLineEnd(fullText, caretPos);
        
        if (lineEnd == fullText.length()) {
            // 已经是最后一行，无法下移
            return;
        }
        
        String currentLine = fullText.substring(lineStart, lineEnd);
        int nextLineEnd = findLineEnd(fullText, lineEnd + 1);
        String nextLine = fullText.substring(lineEnd + 1, nextLineEnd);
        
        String beforeCurrentLine = fullText.substring(0, lineStart);
        String afterNextLine = fullText.substring(nextLineEnd);
        
        String newText = beforeCurrentLine + nextLine + "\n" + currentLine + afterNextLine;
        outputArea.replaceText(newText);
        
        // 移动光标到新位置
        outputArea.moveTo(lineStart + nextLine.length());
    }
    
    /**
     * 切换注释
     */
    private void toggleComment(InlineCssTextArea outputArea) {
        int caretPos = outputArea.getCaretPosition();
        String fullText = outputArea.getText();
        
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        
        int lineStart = findLineStart(fullText, caretPos);
        int lineEnd = findLineEnd(fullText, caretPos);
        String currentLine = fullText.substring(lineStart, lineEnd);
        
        String modifiedLine;
        if (currentLine.trim().startsWith("# ")) {
            // 移除注释
            modifiedLine = currentLine.replaceFirst("^\\s*#\\s+", "");
        } else {
            // 添加注释
            modifiedLine = currentLine.replaceFirst("^(\\s*)", "$1# ");
        }
        
        String beforeLine = fullText.substring(0, lineStart);
        String afterLine = fullText.substring(lineEnd);
        
        outputArea.replaceText(beforeLine + modifiedLine + afterLine);
        outputArea.moveTo(lineStart + modifiedLine.length());
    }
    
    /**
     * 插入注释块
     */
    private void insertCommentBlock(InlineCssTextArea outputArea) {
        String commentBlock = "# ===========================================\n" +
                             "# \n" +
                             "# ===========================================\n";
        
        outputArea.insertText(outputArea.getCaretPosition(), commentBlock);
    }
    
    /**
     * 格式化当前行
     */
    private void formatCurrentLine(InlineCssTextArea outputArea) {
        int caretPos = outputArea.getCaretPosition();
        String fullText = outputArea.getText();
        
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        
        int lineStart = findLineStart(fullText, caretPos);
        int lineEnd = findLineEnd(fullText, caretPos);
        String currentLine = fullText.substring(lineStart, lineEnd);
        
        // 简单的格式化：规范化空格
        String formattedLine = currentLine.replaceAll("\\s+", " ").trim();
        
        String beforeLine = fullText.substring(0, lineStart);
        String afterLine = fullText.substring(lineEnd);
        
        outputArea.replaceText(beforeLine + formattedLine + afterLine);
        outputArea.moveTo(lineStart + formattedLine.length());
    }
    
    /**
     * 查找行的开始位置
     */
    private int findLineStart(String text, int caretPos) {
        if (text == null) return 0;
        int lineStart = text.lastIndexOf('\n', caretPos - 1);
        if (lineStart == -1) lineStart = 0;
        else lineStart++; // 如果没有找到换行符，从文本开头开始
        return lineStart;
    }

    /**
     * 查找行的结束位置
     */
    private int findLineEnd(String text, int caretPos) {
        if (text == null) return 0;
        int lineEnd = text.indexOf('\n', caretPos);
        if (lineEnd == -1) lineEnd = text.length(); // 如果没有找到换行符，到文本结尾结束
        return lineEnd;
    }
}