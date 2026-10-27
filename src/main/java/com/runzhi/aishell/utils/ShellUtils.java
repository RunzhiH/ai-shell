/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.utils;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.text.TextFlow;
import org.fxmisc.richtext.InlineCssTextArea;

/**
 * Shell工具类
 * 提供通用的Shell相关功能，整合重复的shell相关代码
 */
public class ShellUtils {

    // 常见的Linux/Unix命令列表
    public static final String[] COMMON_SHELL_COMMANDS = {
        "ls", "cd", "pwd", "cat", "grep", "find", "cp", "mv", "rm", "mkdir", 
        "rmdir", "touch", "chmod", "chown", "ps", "top", "htop", "kill", 
        "ping", "wget", "curl", "scp", "ssh", "df", "du", "free", "uname", 
        "whoami", "date", "cal", "man", "history", "echo", "export", "alias",
        "clear", "less", "more", "head", "tail", "sort", "uniq", "wc", "cut",
        "paste", "join", "diff", "cmp", "comm", "tr", "sed", "awk", "tar",
        "gzip", "gunzip", "zip", "unzip", "which", "whereis", "locate", "find",
        "ifconfig", "netstat", "route", "nslookup", "dig", "traceroute", "telnet",
        "ftp", "sftp", "rsync", "screen", "tmux", "nano", "vim", "vi", "emacs",
        "env", "printenv", "crontab", "at", "jobs", "fg", "bg", "nohup", "nice",
        "renice", "uptime", "w", "last", "login", "logout", "exit", "su", "sudo",
        "passwd", "groupadd", "useradd", "userdel", "usermod", "groupmod", "chgrp",
        "chfn", "chsh", "mount", "umount", "fsck", "mkfs", "fdisk", "parted",
        "lsblk", "blkid", "lsof", "fuser", "killall", "pkill", "pgrep", "pidof",
        "git", "docker", "kubectl", "npm", "node", "python", "pip", "make", "gcc"
    };

    /**
     * 检测是否为潜在危险命令
     */
    public static boolean isPotentiallyDangerousCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        
        String lowerCmd = command.toLowerCase().trim();
        
        // 常见的危险命令模式
        String[] dangerousPatterns = {
            "rm\\s+-rf",      // 强制删除
            "rm\\s+/",        // 删除根目录
            "mv\\s+.*\\s+/",  // 移动到根目录
            "dd\\s+if=",      // 磁盘操作
            "mkfs\\s+",       // 格式化磁盘
            "fdisk\\s+",      // 分区操作
            ">\\s*/etc/",     // 重定向到系统配置文件
            ">/dev/sd",       // 重定向到设备文件
            "chmod\\s+777\\s+", // 设置过度权限
            "chmod\\s+-R\\s+777", // 递归设置过度权限
            "chown\\s+-R\\s+.*\\s+/home/", // 递归更改home目录所有权
            "mount\\s+-o\\s+remount", // 重新挂载
            "umount\\s+/"     // 卸载根目录
        };
        
        for (String pattern : dangerousPatterns) {
            if (lowerCmd.matches(".*" + pattern + ".*")) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 在TextFlow中追加带颜色的文本
     */
    public static void appendColoredTextToFlow(TextFlow textFlow, String text, String color) {
        javafx.scene.text.Text textNode = new javafx.scene.text.Text(text);
        textNode.setStyle("-fx-fill: " + color + ";");
        Platform.runLater(() -> textFlow.getChildren().add(textNode));
    }

    /**
     * 在TextFlow中追加普通文本
     */
    public static void appendToTextFlow(TextFlow textFlow, String text) {
        appendColoredTextToFlow(textFlow, text, "#d4d4d4"); // 默认颜色
    }

    /**
     * 在TextFlow中追加带颜色的文本（重载方法）
     */
    public static void appendToTextFlow(TextFlow textFlow, String text, String color) {
        appendColoredTextToFlow(textFlow, text, color);
    }

    /**
     * 清空TextFlow内容
     */
    public static void clearTextFlow(TextFlow textFlow) {
        Platform.runLater(() -> textFlow.getChildren().clear());
    }

    /**
     * 显示错误对话框
     */
    public static void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("终端错误");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * 检测是否为有效的shell命令
     */
    public static boolean isValidShellCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        
        String cmd = command.trim().split("\\s+")[0]; // 获取命令部分
        
        for (String commonCmd : COMMON_SHELL_COMMANDS) {
            if (commonCmd.equals(cmd)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 获取命令的描述信息
     */
    public static String getCommandDescription(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "未知命令";
        }
        
        String cmd = command.trim().split("\\s+")[0].toLowerCase();
        
        switch (cmd) {
            case "ls":
                return "列出目录内容";
            case "cd":
                return "更改当前目录";
            case "pwd":
                return "显示当前工作目录";
            case "cat":
                return "连接文件并打印到标准输出";
            case "grep":
                return "全局正则表达式匹配";
            case "find":
                return "在目录层次结构中搜索文件";
            case "cp":
                return "复制文件或目录";
            case "mv":
                return "移动或重命名文件";
            case "rm":
                return "删除文件或目录";
            case "mkdir":
                return "创建目录";
            case "rmdir":
                return "删除空目录";
            case "touch":
                return "修改文件时间戳或创建空文件";
            case "chmod":
                return "改变文件权限";
            case "chown":
                return "改变文件所有者";
            case "ps":
                return "显示进程状态";
            case "top":
                return "显示系统活跃进程";
            case "htop":
                return "交互式的进程查看器";
            case "kill":
                return "终止进程";
            case "ping":
                return "测试网络连接";
            case "wget":
                return "从网络下载文件";
            case "curl":
                return "传输数据的命令行工具";
            case "scp":
                return "安全复制文件";
            case "ssh":
                return "安全外壳协议";
            case "df":
                return "显示磁盘空间使用情况";
            case "du":
                return "估计文件空间使用量";
            case "free":
                return "显示内存使用情况";
            case "uname":
                return "显示系统信息";
            case "whoami":
                return "显示当前用户名";
            case "date":
                return "显示或设置日期时间";
            case "cal":
                return "显示日历";
            case "man":
                return "显示命令手册页";
            case "history":
                return "显示命令历史";
            case "echo":
                return "显示一行文本";
            case "export":
                return "设置环境变量";
            case "alias":
                return "创建命令别名";
            case "clear":
                return "清空终端屏幕";
            case "less":
                return "分页显示文本文件";
            case "more":
                return "分页显示文本文件";
            case "head":
                return "显示文件开头部分";
            case "tail":
                return "显示文件结尾部分";
            case "sort":
                return "对文本行排序";
            case "uniq":
                return "报告或忽略重复行";
            case "wc":
                return "统计文件信息";
            case "cut":
                return "从每行中提取字段";
            case "paste":
                return "合并行";
            case "join":
                return "关联文件";
            case "diff":
                return "比较文件差异";
            case "cmp":
                return "比较文件";
            case "comm":
                return "比较有序文件";
            case "tr":
                return "转换或删除字符";
            case "sed":
                return "流编辑器";
            case "awk":
                return "文本处理语言";
            case "tar":
                return "打包或解包归档文件";
            case "gzip":
                return "压缩或解压文件";
            case "gunzip":
                return "解压gzip文件";
            case "zip":
                return "打包并压缩文件";
            case "unzip":
                return "解压zip文件";
            case "which":
                return "显示命令的完整路径";
            case "whereis":
                return "定位二进制文件";
            case "locate":
                return "查找文件";
            case "ifconfig":
                return "配置网络接口";
            case "netstat":
                return "显示网络连接";
            case "route":
                return "显示路由表";
            case "nslookup":
                return "查询DNS";
            case "dig":
                return "DNS查询工具";
            case "traceroute":
                return "跟踪路由路径";
            case "telnet":
                return "Telnet协议客户端";
            case "ftp":
                return "文件传输协议客户端";
            case "sftp":
                return "安全文件传输协议客户端";
            case "rsync":
                return "同步文件和目录";
            case "screen":
                return "终端复用器";
            case "tmux":
                return "终端复用器";
            case "nano":
                return "简单文本编辑器";
            case "vim":
            case "vi":
                return "文本编辑器";
            case "emacs":
                return "文本编辑器";
            case "env":
                return "显示环境变量";
            case "printenv":
                return "打印环境变量";
            case "crontab":
                return "定时任务表";
            case "at":
                return "在指定时间执行命令";
            case "jobs":
                return "列出作业";
            case "fg":
                return "将后台作业调至前台";
            case "bg":
                return "将作业放到后台";
            case "nohup":
                return "忽略挂起信号";
            case "nice":
                return "设置进程优先级";
            case "renice":
                return "修改进程优先级";
            case "uptime":
                return "显示系统运行时间";
            case "w":
                return "显示谁在线";
            case "last":
                return "显示最近登录信息";
            case "login":
                return "登录系统";
            case "logout":
                return "登出当前会话";
            case "exit":
                return "退出shell";
            case "su":
                return "切换用户";
            case "sudo":
                return "以超级用户身份执行命令";
            case "passwd":
                return "修改密码";
            case "groupadd":
                return "添加组";
            case "useradd":
                return "添加用户";
            case "userdel":
                return "删除用户";
            case "usermod":
                return "修改用户属性";
            case "groupmod":
                return "修改组属性";
            case "chgrp":
                return "改变文件组";
            case "chfn":
                return "改变用户信息";
            case "chsh":
                return "改变登录shell";
            case "mount":
                return "挂载文件系统";
            case "umount":
                return "卸载文件系统";
            case "fsck":
                return "文件系统检查";
            case "mkfs":
                return "创建文件系统";
            case "fdisk":
                return "磁盘分区表操作";
            case "parted":
                return "磁盘分区工具";
            case "lsblk":
                return "列出块设备";
            case "blkid":
                return "块设备属性显示";
            case "lsof":
                return "列出打开的文件";
            case "fuser":
                return "显示使用文件的进程";
            case "killall":
                return "杀死进程";
            case "pkill":
                return "按名称杀死进程";
            case "pgrep":
                return "按名称查找进程";
            case "pidof":
                return "查找进程ID";
            case "git":
                return "分布式版本控制系统";
            case "docker":
                return "容器化平台";
            case "kubectl":
                return "Kubernetes命令行工具";
            case "npm":
                return "Node.js包管理器";
            case "node":
                return "Node.js运行时";
            case "python":
                return "Python解释器";
            case "pip":
                return "Python包安装器";
            case "make":
                return "构建工具";
            case "gcc":
                return "GNU编译器集合";
            default:
                return "未知命令";
        }
    }

    /**
     * 检查命令是否包含管道
     */
    public static boolean containsPipe(String command) {
        if (command == null) {
            return false;
        }
        return command.contains("|");
    }

    /**
     * 检查命令是否包含重定向
     */
    public static boolean containsRedirection(String command) {
        if (command == null) {
            return false;
        }
        return command.contains(">") || command.contains(">>") || command.contains("<") || command.contains("2>");
    }

    /**
     * 获取当前工作目录的名称
     */
    public static String getCurrentDirectoryName() {
        return System.getProperty("user.dir").substring(System.getProperty("user.dir").lastIndexOf(System.getProperty("file.separator")) + 1);
    }

    /**
     * 获取当前用户名
     */
    public static String getCurrentUserName() {
        return System.getProperty("user.name");
    }

    /**
     * 检查是否为Windows系统
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 检查是否为Unix-like系统（Linux, macOS等）
     */
    public static boolean isUnix() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("linux") || os.contains("mac os x") || os.contains("unix");
    }

    /**
     * 清空终端输出区域
     */
    public static void clearTerminalOutput(InlineCssTextArea outputArea) {
        if (outputArea != null) {
            Platform.runLater(outputArea::clear);
        }
    }
}