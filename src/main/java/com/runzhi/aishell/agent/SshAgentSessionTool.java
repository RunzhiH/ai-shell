/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.FileInfo;

import java.util.List;
import java.util.Objects;

/** Resolves Agent operations against an existing, UI-owned SSH tab session. */
public final class SshAgentSessionTool implements AgentSessionTool {
    private final SSHService sshService;

    public SshAgentSessionTool(SSHService sshService) {
        this.sshService = Objects.requireNonNull(sshService, "sshService");
    }

    @Override
    public boolean isConnected(String sessionId) {
        SSHConnectionManager connection = sshService.getTabConnection(sessionId);
        return connection != null && connection.isConnected();
    }

    @Override
    public String workingDirectory(String sessionId) {
        requireConnected(sessionId);
        return sshService.getCurrentWorkingDirectoryForTab(sessionId);
    }

    @Override
    public List<FileInfo> listRemoteFiles(String sessionId, String path) throws Exception {
        requireConnected(sessionId);
        return List.copyOf(sshService.listFilesForTab(sessionId, path));
    }

    @Override
    public String readRemoteTextFile(String sessionId, String path, int maxBytes) throws Exception {
        requireConnected(sessionId);
        return sshService.readTextFileForTab(sessionId, path, maxBytes);
    }

    @Override
    public String systemInfo(String sessionId) throws Exception {
        return requireConnected(sessionId).getSystemInfo();
    }

    @Override public String diskUsage(String sessionId) throws Exception {
        return requireConnected(sessionId).executeCommand("LC_ALL=C df -hP");
    }

    @Override public String memoryUsage(String sessionId) throws Exception {
        return requireConnected(sessionId).executeCommand("LC_ALL=C free -h 2>/dev/null || vm_stat");
    }

    @Override public String processSnapshot(String sessionId) throws Exception {
        return requireConnected(sessionId).executeCommand("LC_ALL=C ps -eo pid,ppid,user,%cpu,%mem,stat,comm --sort=-%cpu | head -n 31");
    }

    @Override public String networkListeners(String sessionId) throws Exception {
        return requireConnected(sessionId).executeCommand("LC_ALL=C ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null");
    }

    /** Executes through the already-bound session without exposing connection parameters. */
    @Override
    public String executeCommand(String sessionId, String command) throws Exception {
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A valid command is required");
        }
        AgentSandboxPolicy.Verdict verdict = AgentSandboxPolicy.validate(command);
        if (!verdict.allowed()) throw new SecurityException("Sandbox rejected command: " + verdict.reason());
        return requireConnected(sessionId).executeAgentCommand(command, 30_000, 1024 * 1024);
    }

    @Override
    public String writeTextFile(String sessionId, String path, String content) throws Exception {
        requireConnected(sessionId);
        sshService.writeTextFileForTab(sessionId, path, content);
        return content;
    }

    @Override
    public void createRemoteDirectory(String sessionId, String path) throws Exception {
        requireConnected(sessionId);
        sshService.createDirectoryForTab(sessionId, path);
    }

    @Override
    public void deleteRemotePath(String sessionId, String path, boolean directory) throws Exception {
        requireConnected(sessionId);
        sshService.deletePathForTab(sessionId, path, directory);
    }

    @Override
    public void renameRemotePath(String sessionId, String from, String to) throws Exception {
        requireConnected(sessionId);
        sshService.renamePathForTab(sessionId, from, to);
    }

    @Override
    public boolean remotePathExists(String sessionId, String path) throws Exception {
        requireConnected(sessionId);
        return sshService.remotePathExistsForTab(sessionId, path);
    }

    private SSHConnectionManager requireConnected(String sessionId) {
        SSHConnectionManager connection = sshService.getTabConnection(sessionId);
        if (connection == null || !connection.isConnected()) {
            throw new IllegalStateException("Target session is not connected");
        }
        return connection;
    }
}
