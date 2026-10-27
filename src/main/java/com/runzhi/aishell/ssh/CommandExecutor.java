/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.runzhi.aishell.common.config.AppConstants;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行器类，提供高级的命令执行功能
 */
@Slf4j
public class CommandExecutor {
    
    private final Session session;
    
    public CommandExecutor(Session session) {
        this.session = session;
    }
    
    /**
     * 同步执行命令并返回结果
     * 
     * @param command 要执行的命令
     * @return 命令执行结果
     * @throws JSchException
     * @throws IOException
     */
    public CommandResult executeCommand(String command) throws JSchException, IOException {
        return executeCommand(command, AppConstants.EXEC_CHANNEL_TIMEOUT);
    }
    
    /**
     * 同步执行命令并返回结果，带自定义超时
     * 
     * @param command 要执行的命令
     * @param timeout 超时时间（毫秒）
     * @return 命令执行结果
     * @throws JSchException
     * @throws IOException
     */
    public CommandResult executeCommand(String command, int timeout) throws JSchException, IOException {
        ChannelExec channel = null;
        InputStream in = null;
        InputStream err = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            
            // 设置输入输出流
            in = channel.getInputStream();
            err = channel.getErrStream();
            
            channel.connect(timeout);
            
            // 读取标准输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // 读取错误输出
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(err))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }
            
            // 等待命令完成，获取退出码
            int exitCode = -1;
            long startTime = System.currentTimeMillis();
            while (channel.isConnected() && 
                   (System.currentTimeMillis() - startTime) < timeout) {
                try {
                    exitCode = channel.getExitStatus();
                    if (exitCode != -1) {
                        break;
                    }
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            return new CommandResult(output.toString().trim(), 
                                   errorOutput.toString().trim(), 
                                   exitCode);
            
        } finally {
            // 确保资源被释放
            if (err != null) {
                try {
                    err.close();
                } catch (IOException e) {
                    log.warn("Error closing error stream: {}", e.getMessage());
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    log.warn("Error closing input stream: {}", e.getMessage());
                }
            }
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }
    
    /**
     * 异步执行命令
     * 
     * @param command 要执行的命令
     * @return CompletableFuture，完成时返回命令执行结果
     */
    public CompletableFuture<CommandResult> executeCommandAsync(String command) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeCommand(command);
            } catch (Exception e) {
                log.error("Error executing command asynchronously: {}", e.getMessage(), e);
                return new CommandResult("", e.getMessage(), -1);
            }
        }, com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool());
    }
    
    /**
     * 异步执行命令，带超时
     * 
     * @param command 要执行的命令
     * @param timeout 超时时间（毫秒）
     * @return CompletableFuture，完成时返回命令执行结果
     */
    public CompletableFuture<CommandResult> executeCommandAsync(String command, int timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeCommand(command, timeout);
            } catch (Exception e) {
                log.error("Error executing command asynchronously: {}", e.getMessage(), e);
                return new CommandResult("", e.getMessage(), -1);
            }
        }, com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool());
    }
    
    /**
     * 检查命令是否在指定时间内执行完成
     * 
     * @param command 要执行的命令
     * @param timeout 超时时间（毫秒）
     * @return 命令执行结果，如果超时则返回超时标识
     * @throws JSchException
     * @throws IOException
     */
    public CommandResult executeCommandWithTimeout(String command, int timeout) throws JSchException, IOException {
        CompletableFuture<CommandResult> future = executeCommandAsync(command, timeout);
        
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Command execution timed out");
            // 取消执行中的任务
            future.cancel(true);
            return new CommandResult("", "Command timed out after " + timeout + "ms", -2);
        }
    }
    
    /**
     * 执行一系列命令
     * 
     * @param commands 命令列表
     * @return 命令执行结果列表
     */
    public java.util.List<CommandResult> executeCommands(java.util.List<String> commands) {
        java.util.List<CommandResult> results = new java.util.ArrayList<>();
        
        for (String command : commands) {
            try {
                CommandResult result = executeCommand(command);
                results.add(result);
            } catch (Exception e) {
                log.error("Error executing command: {}", e.getMessage(), e);
                results.add(new CommandResult("", e.getMessage(), -1));
            }
        }
        
        return results;
    }
    
    /**
     * 命令执行结果类
     */
    public static class CommandResult {
        private final String output;
        private final String error;
        private final int exitCode;
        
        public CommandResult(String output, String error, int exitCode) {
            this.output = output;
            this.error = error;
            this.exitCode = exitCode;
        }
        
        public String getOutput() {
            return output;
        }
        
        public String getError() {
            return error;
        }
        
        public int getExitCode() {
            return exitCode;
        }
        
        public boolean isSuccess() {
            return exitCode == 0;
        }
        
        public boolean isTimedOut() {
            return exitCode == -2; // 特殊退出码表示超时
        }
        
        @Override
        public String toString() {
            return "CommandResult{" +
                    "output='" + output + '\'' +
                    ", error='" + error + '\'' +
                    ", exitCode=" + exitCode +
                    ", success=" + isSuccess() +
                    '}';
        }
    }
    
    /**
     * 检查会话是否有效
     * 
     * @return 会话是否有效
     */
    public boolean isValid() {
        return session != null && session.isConnected();
    }
}
