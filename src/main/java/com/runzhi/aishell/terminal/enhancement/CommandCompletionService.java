/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令补全服务
 * 
 * 功能：
 * 1. 内置命令补全（cd, ls, grep 等）
 * 2. 历史命令补全
 * 3. 路径自动补全
 * 4. 自定义命令片段
 */
@Slf4j
public class CommandCompletionService {
    
    private static volatile CommandCompletionService instance;
    
    /** 常用命令列表 */
    private final List<String> commonCommands = Arrays.asList(
        "ls", "cd", "pwd", "mkdir", "rm", "cp", "mv", "cat", "grep",
        "find", "ps", "kill", "top", "htop", "df", "du", "chmod",
        "chown", "tar", "zip", "unzip", "ssh", "scp", "rsync",
        "curl", "wget", "ping", "netstat", "ifconfig", "ip",
        "systemctl", "journalctl", "tail", "head", "less", "more",
        "vim", "nano", "echo", "printf", "awk", "sed", "sort",
        "uniq", "wc", "diff", "patch", "git", "docker", "kubectl"
    );
    
    /** 命令参数映射 */
    private final Map<String, List<String>> commandArguments = new ConcurrentHashMap<>();
    
    /** 历史命令缓存 */
    private final Map<String, List<String>> commandHistory = new ConcurrentHashMap<>();
    
    /** 路径缓存 */
    private final Set<String> pathCache = ConcurrentHashMap.newKeySet();
    
    private CommandCompletionService() {
        initializeCommandArguments();
    }
    
    public static CommandCompletionService getInstance() {
        if (instance == null) {
            synchronized (CommandCompletionService.class) {
                if (instance == null) {
                    instance = new CommandCompletionService();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化命令参数
     */
    private void initializeCommandArguments() {
        // ls 命令
        commandArguments.put("ls", Arrays.asList("-l", "-la", "-lh", "-lt", "-ltr", "-R", "-a"));
        
        // cd 命令
        commandArguments.put("cd", Arrays.asList("..", "../..", "~", "-"));
        
        // grep 命令
        commandArguments.put("grep", Arrays.asList("-r", "-i", "-v", "-n", "-C", "-A", "-B", "--color"));
        
        // find 命令
        commandArguments.put("find", Arrays.asList("-name", "-type", "-size", "-mtime", "-exec"));
        
        // ps 命令
        commandArguments.put("ps", Arrays.asList("aux", "ef", "-ef", "auxww"));
        
        // chmod 命令
        commandArguments.put("chmod", Arrays.asList("+x", "-x", "+w", "-w", "755", "644", "777"));
        
        // git 命令
        commandArguments.put("git", Arrays.asList(
            "status", "add", "commit", "push", "pull", "clone", "branch",
            "merge", "rebase", "log", "diff", "checkout", "reset", "stash"
        ));
        
        // docker 命令
        commandArguments.put("docker", Arrays.asList(
            "ps", "images", "run", "build", "stop", "start", "restart",
            "logs", "exec", "rm", "rmi", "network", "volume"
        ));
        
        log.debug("命令参数初始化完成");
    }
    
    /**
     * 获取补全建议
     * 
     * @param input 当前输入
     * @param tabId 标签页 ID
     * @return 补全建议列表
     */
    public List<String> getCompletions(String input, String tabId) {
        if (input == null || input.trim().isEmpty()) {
            return new ArrayList<>(commonCommands);
        }
        
        List<String> completions = new ArrayList<>();
        String trimmed = input.trim();
        
        // 检查是否是路径补全
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            completions.addAll(completePath(trimmed));
        }
        
        // 检查是否有历史命令匹配
        completions.addAll(completeFromHistory(trimmed, tabId));
        
        // 检查是否是命令补全
        completions.addAll(completeCommand(trimmed));
        
        // 检查是否是参数补全
        completions.addAll(completeArgument(trimmed));
        
        // 去重并排序
        return new ArrayList<>(new TreeSet<>(completions));
    }
    
    /**
     * 命令补全
     */
    private List<String> completeCommand(String input) {
        List<String> result = new ArrayList<>();
        for (String cmd : commonCommands) {
            if (cmd.startsWith(input)) {
                result.add(cmd + " ");
            }
        }
        return result;
    }
    
    /**
     * 参数补全
     */
    private List<String> completeArgument(String input) {
        List<String> result = new ArrayList<>();
        
        // 提取第一个单词作为命令
        String[] parts = input.split("\\s+");
        if (parts.length > 0) {
            String cmd = parts[0];
            List<String> args = commandArguments.get(cmd);
            
            if (args != null) {
                String lastPart = parts[parts.length - 1];
                for (String arg : args) {
                    if (arg.startsWith(lastPart)) {
                        // 如果已经是最后一个参数，直接替换
                        if (parts.length == 1) {
                            result.add(cmd + " " + arg + " ");
                        } else {
                            // 构建完整的命令
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < parts.length - 1; i++) {
                                sb.append(parts[i]).append(" ");
                            }
                            sb.append(arg).append(" ");
                            result.add(sb.toString());
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * 从历史命令补全
     */
    private List<String> completeFromHistory(String input, String tabId) {
        List<String> result = new ArrayList<>();
        List<String> history = commandHistory.get(tabId);
        
        if (history != null) {
            for (String cmd : history) {
                if (cmd.startsWith(input)) {
                    result.add(cmd);
                }
            }
        }
        
        return result;
    }
    
    /**
     * 路径补全
     */
    private List<String> completePath(String input) {
        List<String> result = new ArrayList<>();
        
        // 简单实现：返回缓存的路径
        for (String path : pathCache) {
            if (path.startsWith(input)) {
                result.add(path);
            }
        }
        
        return result;
    }
    
    /**
     * 添加命令到历史
     * 
     * @param command 命令
     * @param tabId 标签页 ID
     */
    public void addToHistory(String command, String tabId) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        
        commandHistory.computeIfAbsent(tabId, k -> new ArrayList<>());
        List<String> history = commandHistory.get(tabId);
        
        // 避免重复
        if (!history.contains(command)) {
            history.add(command);
            
            // 限制历史记录数量
            if (history.size() > 100) {
                history.remove(0);
            }
        }
        
        log.debug("添加命令到历史：{} (tab: {})", command, tabId);
    }
    
    /**
     * 获取历史命令
     * 
     * @param tabId 标签页 ID
     * @return 历史命令列表
     */
    public List<String> getHistory(String tabId) {
        return new ArrayList<>(commandHistory.getOrDefault(tabId, new ArrayList<>()));
    }
    
    /**
     * 清除历史
     * 
     * @param tabId 标签页 ID
     */
    public void clearHistory(String tabId) {
        commandHistory.remove(tabId);
        log.info("已清除 tab {} 的命令历史", tabId);
    }

    public boolean removeFromHistory(String command, String tabId) {
        List<String> history = commandHistory.get(tabId);
        boolean removed = history != null && history.remove(command);
        if (history != null && history.isEmpty()) commandHistory.remove(tabId);
        return removed;
    }
    
    /**
     * 添加路径到缓存
     * 
     * @param path 路径
     */
    public void addPathToCache(String path) {
        if (path != null && !path.isEmpty()) {
            pathCache.add(path);
        }
    }
    
    /**
     * 获取所有可用命令
     * 
     * @return 命令列表
     */
    public List<String> getAllCommands() {
        return new ArrayList<>(commonCommands);
    }
}
