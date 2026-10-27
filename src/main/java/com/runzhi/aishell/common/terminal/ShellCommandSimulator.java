/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.terminal;

import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shell命令模拟器
 * 提供基本的shell命令模拟功能，用于增强终端体验
 */
@Slf4j
public class ShellCommandSimulator {
    
    private String currentWorkingDirectory;
    private final Map<String, CommandHandler> commandHandlers;
    
    public ShellCommandSimulator() {
        this.currentWorkingDirectory = System.getProperty("user.home");
        this.commandHandlers = new HashMap<>();
        registerDefaultCommands();
    }
    
    /**
     * 注册默认命令处理器
     */
    private void registerDefaultCommands() {
        // pwd命令
        commandHandlers.put("pwd", this::handlePwd);
        
        // echo命令
        commandHandlers.put("echo", this::handleEcho);
        
        // date命令
        commandHandlers.put("date", this::handleDate);
        
        // whoami命令
        commandHandlers.put("whoami", this::handleWhoami);
        
        // ls命令
        commandHandlers.put("ls", this::handleLs);
        
        // cd命令
        commandHandlers.put("cd", this::handleCd);
        
        // mkdir命令
        commandHandlers.put("mkdir", this::handleMkdir);
        
        // touch命令
        commandHandlers.put("touch", this::handleTouch);
        
        // cat命令
        commandHandlers.put("cat", this::handleCat);
        
        // clear命令
        commandHandlers.put("clear", this::handleClear);
        
        // help命令
        commandHandlers.put("help", this::handleHelp);
        
        // history命令
        commandHandlers.put("history", this::handleHistory);
        
        // exit命令
        commandHandlers.put("exit", this::handleExit);
        
        // env命令
        commandHandlers.put("env", this::handleEnv);
        
        // uname命令
        commandHandlers.put("uname", this::handleUname);
    }
    
    /**
     * 处理命令
     */
    public String executeCommand(String command, InlineCssTextArea outputArea) {
        if (command == null || command.trim().isEmpty()) {
            return "";
        }
        
        String[] parts = command.trim().split("\\s+", 2);
        String cmd = parts[0];
        String args = parts.length > 1 ? parts[1] : "";
        
        CommandHandler handler = commandHandlers.get(cmd);
        if (handler != null) {
            try {
                String result = handler.handle(args, outputArea);
                return result;
            } catch (Exception e) {
                log.error("执行命令 '{}' 时出错: {}", command, e.getMessage(), e);
                return "错误: " + e.getMessage();
            }
        } else {
            return "bash: " + cmd + ": command not found\n";
        }
    }
    
    /**
     * 检查命令是否被模拟器支持
     */
    public boolean isSupportedCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        
        String cmd = command.trim().split("\\s+")[0];
        return commandHandlers.containsKey(cmd);
    }
    
    /**
     * 获取当前工作目录
     */
    public String getCurrentWorkingDirectory() {
        return currentWorkingDirectory;
    }
    
    /**
     * 模拟执行命令（简化版本，不依赖输出区域）
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    public String simulateCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "";
        }
        
        String[] parts = command.trim().split("\\s+", 2);
        String cmd = parts[0];
        String args = parts.length > 1 ? parts[1] : "";
        
        CommandHandler handler = commandHandlers.get(cmd);
        if (handler != null) {
            try {
                // 创建一个临时的输出区域用于命令处理
                InlineCssTextArea tempOutput = new InlineCssTextArea();
                String result = handler.handle(args, tempOutput);
                return result;
            } catch (Exception e) {
                log.error("执行命令 '{}' 时出错：{}", command, e.getMessage(), e);
                return "错误：" + e.getMessage();
            }
        } else {
            return "bash: " + cmd + ": command not found\n";
        }
    }
    
    /**
     * 设置当前工作目录
     */
    public void setCurrentWorkingDirectory(String path) {
        if (path != null) {
            // 处理相对路径
            if (path.startsWith("~/")) {
                path = System.getProperty("user.home") + path.substring(1);
            } else if (!path.startsWith("/") && !path.contains(":")) { // Windows路径检查
                if (!currentWorkingDirectory.endsWith("/")) {
                    path = currentWorkingDirectory + "/" + path;
                } else {
                    path = currentWorkingDirectory + path;
                }
            }
            
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                this.currentWorkingDirectory = path;
            } else {
                log.warn("目录不存在: {}", path);
            }
        }
    }
    
    // 命令处理方法
    private String handlePwd(String args, InlineCssTextArea outputArea) {
        return currentWorkingDirectory + "\n";
    }
    
    private String handleEcho(String args, InlineCssTextArea outputArea) {
        return args + "\n";
    }
    
    private String handleDate(String args, InlineCssTextArea outputArea) {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy")) + "\n";
    }
    
    private String handleWhoami(String args, InlineCssTextArea outputArea) {
        return System.getProperty("user.name") + "\n";
    }
    
    private String handleLs(String args, InlineCssTextArea outputArea) {
        try {
            Path path = Paths.get(currentWorkingDirectory);
            List<Path> entries = Files.list(path)
                    .sorted((p1, p2) -> {
                        boolean b1 = Files.isDirectory(p1);
                        boolean b2 = Files.isDirectory(p2);
                        if (b1 && !b2) return -1;
                        if (!b1 && b2) return 1;
                        return p1.compareTo(p2);
                    })
                    .collect(Collectors.toList());
            
            StringBuilder result = new StringBuilder();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    result.append(name).append("/\n");
                } else {
                    result.append(name).append("\n");
                }
            }
            return result.toString();
        } catch (IOException e) {
            return "ls: cannot access '" + currentWorkingDirectory + "': " + e.getMessage() + "\n";
        }
    }
    
    private String handleCd(String args, InlineCssTextArea outputArea) {
        if (args.isEmpty()) {
            setCurrentWorkingDirectory(System.getProperty("user.home"));
        } else {
            setCurrentWorkingDirectory(args.trim());
        }
        return "";
    }
    
    private String handleMkdir(String args, InlineCssTextArea outputArea) {
        if (args.isEmpty()) {
            return "mkdir: missing operand\n";
        }
        
        String[] dirs = args.split("\\s+");
        StringBuilder result = new StringBuilder();
        
        for (String dir : dirs) {
            Path path = Paths.get(resolvePath(dir));
            try {
                Files.createDirectories(path);
                result.append("");
            } catch (IOException e) {
                result.append("mkdir: cannot create directory '").append(dir).append("': ")
                      .append(e.getMessage()).append("\n");
            }
        }
        
        return result.toString();
    }
    
    private String handleTouch(String args, InlineCssTextArea outputArea) {
        if (args.isEmpty()) {
            return "touch: missing file operand\n";
        }
        
        String[] files = args.split("\\s+");
        StringBuilder result = new StringBuilder();
        
        for (String file : files) {
            Path path = Paths.get(resolvePath(file));
            try {
                Files.createFile(path);
                result.append("");
            } catch (IOException e) {
                // 如果文件已存在，更新时间戳
                try {
                    Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                } catch (IOException e2) {
                    result.append("touch: cannot touch '").append(file).append("': ")
                          .append(e2.getMessage()).append("\n");
                }
            }
        }
        
        return result.toString();
    }
    
    private String handleCat(String args, InlineCssTextArea outputArea) {
        if (args.isEmpty()) {
            return "cat: missing file operand\n";
        }
        
        Path path = Paths.get(resolvePath(args));
        try {
            String content = Files.readString(path);
            return content + "\n";
        } catch (IOException e) {
            return "cat: " + args + ": " + e.getMessage() + "\n";
        }
    }
    
    private String handleClear(String args, InlineCssTextArea outputArea) {
        Platform.runLater(() -> outputArea.clear());
        return "";
    }
    
    private String handleHelp(String args, InlineCssTextArea outputArea) {
        StringBuilder helpText = new StringBuilder();
        helpText.append("支持的命令列表:\n");
        helpText.append("  pwd     - 显示当前工作目录\n");
        helpText.append("  echo    - 显示文本\n");
        helpText.append("  date    - 显示当前日期和时间\n");
        helpText.append("  whoami  - 显示当前用户名\n");
        helpText.append("  ls      - 列出目录内容\n");
        helpText.append("  cd      - 更改目录\n");
        helpText.append("  mkdir   - 创建目录\n");
        helpText.append("  touch   - 创建文件或更新时间戳\n");
        helpText.append("  cat     - 显示文件内容\n");
        helpText.append("  clear   - 清屏\n");
        helpText.append("  help    - 显示此帮助信息\n");
        helpText.append("  history - 显示命令历史\n");
        helpText.append("  exit    - 退出\n");
        helpText.append("  env     - 显示环境变量\n");
        helpText.append("  uname   - 显示系统信息\n");
        helpText.append("\n");
        return helpText.toString();
    }
    
    private String handleHistory(String args, InlineCssTextArea outputArea) {
        // 这里需要访问命令历史管理器
        return "命令历史功能需要通过终端服务访问\n";
    }
    
    private String handleExit(String args, InlineCssTextArea outputArea) {
        // 退出逻辑应由上层控制
        return "exit\n";
    }
    
    private String handleEnv(String args, InlineCssTextArea outputArea) {
        StringBuilder envVars = new StringBuilder();
        System.getenv().forEach((key, value) -> {
            envVars.append(key).append("=").append(value).append("\n");
        });
        return envVars.toString();
    }
    
    private String handleUname(String args, InlineCssTextArea outputArea) {
        StringBuilder result = new StringBuilder();
        result.append("系统: ").append(System.getProperty("os.name")).append("\n");
        result.append("架构: ").append(System.getProperty("os.arch")).append("\n");
        result.append("版本: ").append(System.getProperty("os.version")).append("\n");
        result.append("Java版本: ").append(System.getProperty("java.version")).append("\n");
        return result.toString();
    }
    
    /**
     * 解析路径（处理相对路径和波浪号）
     */
    private String resolvePath(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        } else if (!path.startsWith("/") && !path.contains(":") && !path.startsWith("./") && !path.startsWith("../")) {
            // 相对路径
            if (!currentWorkingDirectory.endsWith("/")) {
                return currentWorkingDirectory + "/" + path;
            } else {
                return currentWorkingDirectory + path;
            }
        }
        return path;
    }
    
    /**
     * 命令处理器接口
     */
    @FunctionalInterface
    public interface CommandHandler {
        String handle(String args, InlineCssTextArea outputArea) throws Exception;
    }
}