/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement.modules;

import javafx.geometry.Point2D;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.fxmisc.richtext.InlineCssTextArea;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 增强型命令补全模块
 * 提供更智能的命令和路径补全功能
 */
public class EnhancedCommandCompletionModule implements TerminalEnhancementModule {
    
    private final Set<String> enabledTabs = new HashSet<>();
    private final Set<String> commonCommands;
    private final Popup completionPopup;
    private final Label popupLabel;
    private String currentCompletion;
    private List<String> completionOptions;
    private int currentOptionIndex = 0;
    
    public EnhancedCommandCompletionModule() {
        // 初始化常用命令集合
        commonCommands = initializeCommonCommands();
        
        // 初始化补全弹窗
        completionPopup = new Popup();
        popupLabel = new Label();
        popupLabel.setStyle(
            "-fx-background-color: #2d2d2d; " +
            "-fx-text-fill: #ffffff; " +
            "-fx-padding: 5px; " +
            "-fx-border-color: #555555; " +
            "-fx-border-width: 1px; " +
            "-fx-font-family: 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px;"
        );
        VBox popupContent = new VBox(popupLabel);
        popupContent.setStyle("-fx-background-color: #2d2d2d;");
        completionPopup.getContent().add(popupContent);
    }
    
    private Set<String> initializeCommonCommands() {
        Set<String> commands = new HashSet<>();
        commands.add("ls"); commands.add("cd"); commands.add("pwd"); commands.add("cat");
        commands.add("grep"); commands.add("find"); commands.add("cp"); commands.add("mv");
        commands.add("rm"); commands.add("mkdir"); commands.add("rmdir"); commands.add("touch");
        commands.add("chmod"); commands.add("chown"); commands.add("ps"); commands.add("top");
        commands.add("htop"); commands.add("kill"); commands.add("ping"); commands.add("wget");
        commands.add("curl"); commands.add("scp"); commands.add("ssh"); commands.add("df");
        commands.add("du"); commands.add("free"); commands.add("uname"); commands.add("whoami");
        commands.add("date"); commands.add("cal"); commands.add("man"); commands.add("history");
        commands.add("echo"); commands.add("export"); commands.add("alias"); commands.add("clear");
        commands.add("less"); commands.add("more"); commands.add("head"); commands.add("tail");
        commands.add("sort"); commands.add("uniq"); commands.add("wc"); commands.add("cut");
        commands.add("paste"); commands.add("join"); commands.add("diff"); commands.add("cmp");
        commands.add("comm"); commands.add("tr"); commands.add("sed"); commands.add("awk");
        commands.add("tar"); commands.add("gzip"); commands.add("gunzip"); commands.add("zip");
        commands.add("unzip"); commands.add("which"); commands.add("whereis"); commands.add("locate");
        commands.add("ifconfig"); commands.add("netstat"); commands.add("route"); commands.add("nslookup");
        commands.add("dig"); commands.add("traceroute"); commands.add("telnet"); commands.add("ftp");
        commands.add("sftp"); commands.add("rsync"); commands.add("screen"); commands.add("tmux");
        commands.add("nano"); commands.add("vim"); commands.add("vi"); commands.add("emacs");
        commands.add("env"); commands.add("printenv"); commands.add("crontab"); commands.add("at");
        commands.add("jobs"); commands.add("fg"); commands.add("bg"); commands.add("nohup");
        commands.add("nice"); commands.add("renice"); commands.add("uptime"); commands.add("w");
        commands.add("last"); commands.add("login"); commands.add("logout"); commands.add("exit");
        commands.add("su"); commands.add("sudo"); commands.add("passwd"); commands.add("groupadd");
        commands.add("useradd"); commands.add("userdel"); commands.add("usermod"); commands.add("groupmod");
        commands.add("chgrp"); commands.add("chfn"); commands.add("chsh"); commands.add("mount");
        commands.add("umount"); commands.add("fsck"); commands.add("mkfs"); commands.add("fdisk");
        commands.add("parted"); commands.add("lsblk"); commands.add("blkid"); commands.add("lsof");
        commands.add("fuser"); commands.add("killall"); commands.add("pkill"); commands.add("pgrep");
        commands.add("pidof"); commands.add("git"); commands.add("docker"); commands.add("kubectl");
        commands.add("npm"); commands.add("node"); commands.add("python"); commands.add("pip");
        commands.add("make"); commands.add("gcc"); commands.add("systemctl"); commands.add("service");
        commands.add("journalctl"); commands.add("systeminfo"); commands.add("tasklist"); commands.add("ipconfig");
        commands.add("netstat"); commands.add("ping"); commands.add("tracert"); commands.add("arp");
        commands.add("route"); commands.add("nslookup"); commands.add("ip"); commands.add("ss");
        commands.add("lsof"); commands.add("iotop"); commands.add("iostat"); commands.add("vmstat");
        commands.add("sar"); commands.add("topas"); commands.add("prstat"); commands.add("iostat");
        commands.add("mpstat"); commands.add("nmon"); commands.add("htop"); commands.add("atop");
        commands.add("glances"); commands.add("dstat"); commands.add("nethogs"); commands.add("iftop");
        commands.add("tcptrack"); commands.add("pktstat"); commands.add("bandwhich"); commands.add("vnstat");
        commands.add("nload"); commands.add("slurm"); commands.add("speedometer"); commands.add("cbm");
        commands.add("bwm-ng"); commands.add("ifstat"); commands.add("trafshow"); commands.add("netwatch");
        commands.add("iptraf-ng"); commands.add("tcptraceroute"); commands.add("mtr"); commands.add("btrace");
        commands.add("latency"); commands.add("iperf"); commands.add("iperf3"); commands.add("nuttcp");
        commands.add("thrift"); commands.add("sockperf"); commands.add("netperf"); commands.add("lmbench");
        commands.add("unixbench"); commands.add("phoronix-test-suite"); commands.add("stress"); commands.add("stress-ng");
        commands.add("prime95"); commands.add("superpi"); commands.add("linpack"); commands.add("spec");
        commands.add("geekbench"); commands.add("passmark"); commands.add("pcmark"); commands.add("3dmark");
        commands.add("unigine"); commands.add("glmark2"); commands.add("parboil"); commands.add("rodinia");
        commands.add("splash2"); commands.add("canneal"); commands.add("stream"); commands.add("ramspeed");
        commands.add("cpuburn"); commands.add("gpu-burn"); commands.add("fio"); commands.add("bonnie++");
        commands.add("dbench"); commands.add("tiobench"); commands.add("iozone"); commands.add("postmark");
        commands.add("mdadm"); commands.add("smartctl"); commands.add("hdparm"); commands.add("badblocks");
        commands.add("fsck"); commands.add("e2fsck"); commands.add("resize2fs"); commands.add("tune2fs");
        commands.add("dumpe2fs"); commands.add("debugfs"); commands.add("xfs_repair"); commands.add("xfs_check");
        commands.add("btrfs-check"); commands.add("btrfs-rescue"); commands.add("zfs"); commands.add("zpool");
        commands.add("lvcreate"); commands.add("lvremove"); commands.add("lvextend"); commands.add("lvreduce");
        commands.add("vgcreate"); commands.add("vgremove"); commands.add("vgextend"); commands.add("vgreduce");
        commands.add("pvcreate"); commands.add("pvremove"); commands.add("pvdisplay"); commands.add("vgdisplay");
        commands.add("lvdisplay"); commands.add("lvs"); commands.add("vgs"); commands.add("pvs");
        return commands;
    }

    @Override
    public void initialize() {
        // 初始化增强型命令补全模块
    }

    @Override
    public void enable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.add(tabId);
        }
        
        // 为输出区域添加键盘事件监听器
        // addEventFilter 而非 setOnKeyPressed：避免被后注册的模块覆盖导致 TAB 补全静默失效（2026-10-23 审计 P1-14）
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> handler =
                event -> handleCompletion(event, outputArea);
        keyHandlers.put(outputArea, handler);
        outputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, handler);
    }

    private final java.util.Map<InlineCssTextArea, javafx.event.EventHandler<javafx.scene.input.KeyEvent>> keyHandlers =
            new java.util.IdentityHashMap<>();

    @Override
    public void disable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.remove(tabId);
        }

        // 摘除键盘过滤器并隐藏补全弹窗，否则禁用后 TAB 补全照常工作（2026-10-23 审计终端-10）
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> handler = keyHandlers.remove(outputArea);
        if (handler != null) {
            outputArea.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, handler);
        }
        hideCompletionPopup();
    }

    @Override
    public boolean isEnabled(String tabId) {
        return tabId != null && enabledTabs.contains(tabId);
    }

    @Override
    public String getModuleName() {
        return "enhanced-command-completion";
    }

    @Override
    public String getDescription() {
        return "提供增强型智能命令和路径补全功能";
    }
    
    /**
     * 处理补全事件
     */
    private void handleCompletion(KeyEvent event, InlineCssTextArea outputArea) {
        // 模块被禁用（enabledTabs 为空）时不再处理按键（2026-10-23 审计终端-10）
        if (enabledTabs.isEmpty()) return;
        if (event.getCode() == KeyCode.TAB) {
            handleTabCompletion(outputArea);
            event.consume();
        } else if (event.getCode() == KeyCode.DOWN && completionPopup.isShowing()) {
            // 选择下一个补全选项
            selectNextCompletionOption();
            event.consume();
        } else if (event.getCode() == KeyCode.UP && completionPopup.isShowing()) {
            // 选择上一个补全选项
            selectPreviousCompletionOption();
            event.consume();
        } else if (event.getCode() == KeyCode.ENTER && completionPopup.isShowing()) {
            // 使用选中的补全选项
            useSelectedCompletion(outputArea);
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE && completionPopup.isShowing()) {
            // 取消补全
            hideCompletionPopup();
            event.consume();
        }
    }
    
    /**
     * 处理Tab键补全
     */
    private void handleTabCompletion(InlineCssTextArea outputArea) {
        String currentText = outputArea.getText();
        int caretPos = outputArea.getCaretPosition();
        
        // 获取当前行的文本
        String currentLine = getCurrentLine(currentText, caretPos);
        
        // 根据当前行内容决定是命令补全还是路径补全
        if (currentLine.contains("/")) {
            // 路径补全
            performPathCompletion(outputArea, currentLine);
        } else {
            // 命令补全
            performCommandCompletion(outputArea, currentLine);
        }
    }
    
    /**
     * 执行命令补全
     */
    private void performCommandCompletion(InlineCssTextArea outputArea, String currentLine) {
        String commandPrefix = extractCommandPrefix(currentLine);
        
        // 查找匹配的命令
        List<String> matches = commonCommands.stream()
                .filter(cmd -> cmd.startsWith(commandPrefix))
                .sorted()
                .collect(Collectors.toList());
        
        if (matches.size() == 1) {
            // 精确匹配，直接替换
            String matchedCommand = matches.get(0);
            replaceCurrentWord(outputArea, commandPrefix, matchedCommand);
        } else if (matches.size() > 1) {
            // 多个匹配项，显示补全弹窗
            showCompletionPopup(outputArea, matches);
        }
    }
    
    /**
     * 执行路径补全
     */
    private void performPathCompletion(InlineCssTextArea outputArea, String currentLine) {
        String pathPrefix = extractPathPrefix(currentLine);
        
        // 获取路径补全建议
        List<String> pathMatches = getPathSuggestions(pathPrefix);
        
        if (pathMatches.size() == 1) {
            // 精确匹配，直接替换
            String matchedPath = pathMatches.get(0);
            replaceCurrentWord(outputArea, pathPrefix, matchedPath);
        } else if (pathMatches.size() > 1) {
            // 多个匹配项，显示补全弹窗
            showCompletionPopup(outputArea, pathMatches);
        }
    }
    
    /**
     * 获取路径建议
     */
    private List<String> getPathSuggestions(String pathPrefix) {
        List<String> suggestions = new ArrayList<>();
        
        try {
            String expandedPath = expandPath(pathPrefix);
            File file = new File(expandedPath);
            
            String parentPath = file.getParent();
            String fileNamePart = file.getName();
            
            if (parentPath == null) {
                parentPath = System.getProperty("user.dir");
            }
            
            File parentDir = new File(parentPath);
            if (parentDir.exists() && parentDir.isDirectory()) {
                File[] files = parentDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        if (name.toLowerCase().startsWith(fileNamePart.toLowerCase())) {
                            String fullPath = f.getAbsolutePath();
                            String relativePath = convertToRelativePath(fullPath, pathPrefix);
                            suggestions.add(relativePath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略异常，返回空列表
        }
        
        // 按字母顺序排序
        suggestions.sort(String.CASE_INSENSITIVE_ORDER);
        return suggestions;
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
     * 提取命令前缀
     */
    private String extractCommandPrefix(String currentLine) {
        String trimmedLine = currentLine.trim();
        String[] parts = trimmedLine.split("\\s+");
        if (parts.length > 0) {
            return parts[0]; // 第一个单词通常是命令
        }
        return trimmedLine;
    }
    
    /**
     * 提取路径前缀
     */
    private String extractPathPrefix(String currentLine) {
        String trimmedLine = currentLine.trim();
        String[] parts = trimmedLine.split("\\s+");
        if (parts.length > 0) {
            // 检查最后一个单词是否包含路径分隔符
            String lastPart = parts[parts.length - 1];
            if (lastPart.contains("/")) {
                return lastPart;
            }
        }
        return trimmedLine;
    }
    
    /**
     * 替换当前单词
     */
    private void replaceCurrentWord(InlineCssTextArea outputArea, String oldWord, String newWord) {
        String fullText = outputArea.getText();
        int caretPos = outputArea.getCaretPosition();
        
        // 找到当前单词的位置
        int wordStart = findWordStart(fullText, caretPos, oldWord);
        if (wordStart != -1) {
            int wordEnd = wordStart + oldWord.length();
            
            String beforeWord = fullText.substring(0, wordStart);
            String afterWord = fullText.substring(wordEnd);
            
            outputArea.replaceText(beforeWord + newWord + afterWord);
            outputArea.moveTo(wordStart + newWord.length());
        }
    }
    
    /**
     * 查找单词的起始位置
     */
    private int findWordStart(String text, int caretPos, String word) {
        // 从光标位置向前搜索
        int searchStart = Math.max(0, caretPos - word.length() - 10); // 给一些额外的搜索空间
        String searchText = text.substring(searchStart, caretPos);
        
        int index = searchText.toLowerCase().lastIndexOf(word.toLowerCase());
        if (index != -1) {
            return searchStart + index;
        }
        
        return -1;
    }
    
    /**
     * 显示补全弹窗
     */
    private void showCompletionPopup(InlineCssTextArea outputArea, List<String> options) {
        if (options.isEmpty()) {
            return;
        }
        
        completionOptions = options;
        currentOptionIndex = 0;
        currentCompletion = options.get(0);
        
        // 更新弹窗内容
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            if (i == currentOptionIndex) {
                sb.append("> ").append(options.get(i)).append("\n");
            } else {
                sb.append("  ").append(options.get(i)).append("\n");
            }
        }
        
        // 只显示前10个项目
        String[] lines = sb.toString().split("\n");
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 10); i++) {
            display.append(lines[i]).append("\n");
        }
        
        popupLabel.setText(display.toString().trim());
        
        // 计算弹窗位置：getCaretBounds() 在光标不可见时返回空 Optional，.get() 会直接 NPE 中断补全（2026-10-23 审计 终端-15）
        javafx.geometry.Bounds caretBounds = outputArea.getCaretBounds().orElse(null);
        double popupX;
        double popupY;
        if (caretBounds != null) {
            Point2D screenPos = outputArea.localToScreen(caretBounds.getMinX(), caretBounds.getMaxY());
            popupX = screenPos == null ? 0 : screenPos.getX();
            popupY = screenPos == null ? 0 : screenPos.getY();
        } else {
            // 光标边界不可用时退回使用文本区域自身位置
            Point2D fallback = outputArea.localToScreen(0, outputArea.getHeight());
            popupX = fallback == null ? 0 : fallback.getX();
            popupY = fallback == null ? 0 : fallback.getY();
        }

        completionPopup.show(outputArea.getScene().getWindow(),
                             popupX,
                             popupY + 5);
    }
    
    /**
     * 选择下一个补全选项
     */
    private void selectNextCompletionOption() {
        if (completionOptions != null && !completionOptions.isEmpty()) {
            currentOptionIndex = (currentOptionIndex + 1) % completionOptions.size();
            updatePopupDisplay();
        }
    }
    
    /**
     * 选择上一个补全选项
     */
    private void selectPreviousCompletionOption() {
        if (completionOptions != null && !completionOptions.isEmpty()) {
            currentOptionIndex = (currentOptionIndex - 1 + completionOptions.size()) % completionOptions.size();
            updatePopupDisplay();
        }
    }
    
    /**
     * 更新弹窗显示
     */
    private void updatePopupDisplay() {
        if (completionOptions != null && !completionOptions.isEmpty()) {
            currentCompletion = completionOptions.get(currentOptionIndex);
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < completionOptions.size(); i++) {
                if (i == currentOptionIndex) {
                    sb.append("> ").append(completionOptions.get(i)).append("\n");
                } else {
                    sb.append("  ").append(completionOptions.get(i)).append("\n");
                }
            }
            
            // 只显示前10个项目
            String[] lines = sb.toString().split("\n");
            StringBuilder display = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, 10); i++) {
                display.append(lines[i]).append("\n");
            }
            
            popupLabel.setText(display.toString().trim());
        }
    }
    
    /**
     * 使用选中的补全
     */
    private void useSelectedCompletion(InlineCssTextArea outputArea) {
        if (currentCompletion != null) {
            String currentLine = getCurrentLine(outputArea.getText(), outputArea.getCaretPosition());
            String wordToReplace = extractCurrentWord(currentLine);
            
            replaceCurrentWord(outputArea, wordToReplace, currentCompletion);
            hideCompletionPopup();
        }
    }
    
    /**
     * 提取当前单词
     */
    private String extractCurrentWord(String currentLine) {
        if (currentLine == null || currentLine.isEmpty()) {
            return "";
        }
        
        int spaceIndex = currentLine.lastIndexOf(' ');
        if (spaceIndex != -1) {
            return currentLine.substring(spaceIndex + 1);
        } else {
            return currentLine;
        }
    }
    
    /**
     * 隐藏补全弹窗
     */
    private void hideCompletionPopup() {
        if (completionPopup.isShowing()) {
            completionPopup.hide();
        }
    }
    
    /**
     * 获取当前行的文本
     */
    private String getCurrentLine(String fullText, int caretPos) {
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
}