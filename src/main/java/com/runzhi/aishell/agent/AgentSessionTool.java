/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.runzhi.aishell.ssh.domain.FileInfo;

import java.util.List;

/**
 * Narrow, credential-free session capability exposed to Agent tools.
 * Implementations resolve an already-open application session from its opaque id;
 * callers never provide host, port, user name, password, or key material.
 */
public interface AgentSessionTool {
    boolean isConnected(String sessionId);

    String workingDirectory(String sessionId);

    List<FileInfo> listRemoteFiles(String sessionId, String path) throws Exception;

    String readRemoteTextFile(String sessionId, String path, int maxBytes) throws Exception;

    String systemInfo(String sessionId) throws Exception;

    default String diskUsage(String sessionId) throws Exception { throw new UnsupportedOperationException(); }

    default String memoryUsage(String sessionId) throws Exception { throw new UnsupportedOperationException(); }

    default String processSnapshot(String sessionId) throws Exception { throw new UnsupportedOperationException(); }

    default String networkListeners(String sessionId) throws Exception { throw new UnsupportedOperationException(); }

    /** Runs a sandbox-validated, non-interactive command through the bound session. */
    default String executeCommand(String sessionId, String command) throws Exception {
        throw new UnsupportedOperationException();
    }

    /** Writes UTF-8 text to a remote file over SFTP through the bound session. */
    default String writeTextFile(String sessionId, String path, String content) throws Exception {
        throw new UnsupportedOperationException();
    }

    default void createRemoteDirectory(String sessionId, String path) throws Exception {
        throw new UnsupportedOperationException();
    }

    default void deleteRemotePath(String sessionId, String path, boolean directory) throws Exception {
        throw new UnsupportedOperationException();
    }

    default void renameRemotePath(String sessionId, String from, String to) throws Exception {
        throw new UnsupportedOperationException();
    }

    default boolean remotePathExists(String sessionId, String path) throws Exception {
        throw new UnsupportedOperationException();
    }
}
