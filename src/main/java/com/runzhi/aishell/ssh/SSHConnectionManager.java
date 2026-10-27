/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.jcraft.jsch.*;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.exception.AIShellException;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.common.validation.InputValidator;
import javafx.application.Platform;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;


@Getter
@Slf4j
public class SSHConnectionManager implements AutoCloseable {
    private static final Preferences SSH_PREFS = Preferences.userRoot().node("/com/runzhi/aishell/controller");
    private Session session;
    private Session jumpSession;
    private int jumpForwardedPort = -1;
    private final Map<String, DynamicSocksForwarder> dynamicForwarders = new ConcurrentHashMap<>();
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String privateKeyPath; // 添加私钥路径支持

    // 每个SSH连接实例都是独立的

    // 添加一个交互式shell通道
    private ChannelShell shellChannel;
    private PrintWriter shellWriter;
    private BufferedReader shellReader;
    private final String shellPrompt = AppConstants.DEFAULT_TERMINAL_PROMPT; // 使用常量

    // 新增持久Shell相关成员变量
    private InputStream shellInputStream;
    private OutputStream shellOutputStream;
    private Thread readerThread;  // 用于实时读取输出
    private final AtomicBoolean running = new AtomicBoolean(false);  // 使用AtomicBoolean替代volatile

    // 添加ChannelExec用于执行特定命令
    private ChannelExec execChannel;

    // 支持多个Tab的输出回调
    private final Set<ShellOutputCallback> outputCallbacks = new java.util.concurrent.CopyOnWriteArraySet<>();

    // 连接状态监听器
    private final CopyOnWriteArrayList<ConnectionStateListener> connectionStateListeners = new CopyOnWriteArrayList<>();

    /**
     * -- SETTER --
     * 设置是否启用自动重连
     */
    // 自动重连相关变量
    @Setter
    private boolean autoReconnectEnabled = true;
    private int reconnectAttempts = 0;
    /**
     * -- SETTER --
     * 设置最大重连尝试次数
     */
    @Setter
    private int maxReconnectAttempts = 5;
    /**
     * -- SETTER --
     * 设置重连延迟时间
     */
    @Setter
    private long reconnectDelay = 5000; // 5秒
    private volatile Thread reconnectThread;
    private boolean reconnecting = false;
    /** 区分用户主动断开与网络异常，避免关闭 Tab 后连接在后台“复活”。 */
    private volatile boolean intentionalDisconnect = false;
    /** Set by an external disconnect to abort an in-flight auto-reconnect; the reconnect thread checks it. */
    private volatile boolean reconnectCancelled = false;

    // 用于跟踪连接的标签页ID（用于调试）
    @Setter
    private String tabId;
    // 存储当前用户和主机信息
    private String currentUser = "user";
    private String currentHost = "localhost";
    private String currentPath = "~";

    // 用于跟踪最后发送的命令，过滤回显
    private String lastSentCommand = "";

    // 用于标记是否期待命令回显
    private final AtomicBoolean expectingCommandEcho = new AtomicBoolean(false);

    // 添加构造函数支持密钥认证
    public SSHConnectionManager(String host, int port, String username, String password, String privateKeyPath) {
        // 输入验证
        InputValidator.validateHostname(host, "主机名");
        InputValidator.validatePort(port, "端口");
        InputValidator.validateUsername(username, "用户名");
        
        // 验证密码或私钥路径（至少提供一种认证方式）
        if ((password == null || password.isEmpty()) && 
            (privateKeyPath == null || privateKeyPath.isEmpty())) {
            throw new IllegalArgumentException("必须提供密码或私钥路径");
        }
        
        if (password != null && !password.isEmpty()) {
            InputValidator.validatePassword(password, "密码");
        }
        
        if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
            InputValidator.validateFilePath(privateKeyPath, "私钥路径");
        }
        
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPath = privateKeyPath;
    }

    // 添加构造函数支持密钥认证和tabId
    public SSHConnectionManager(String host, int port, String username, String password, String privateKeyPath, String tabId) {
        // 输入验证
        InputValidator.validateHostname(host, "主机名");
        InputValidator.validatePort(port, "端口");
        InputValidator.validateUsername(username, "用户名");
        
        // 验证密码或私钥路径（至少提供一种认证方式）
        if ((password == null || password.isEmpty()) && 
            (privateKeyPath == null || privateKeyPath.isEmpty())) {
            throw new IllegalArgumentException("必须提供密码或私钥路径");
        }
        
        if (password != null && !password.isEmpty()) {
            InputValidator.validatePassword(password, "密码");
        }
        
        if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
            InputValidator.validateFilePath(privateKeyPath, "私钥路径");
        }
        
        // 验证tabId
        if (tabId != null && !tabId.isEmpty()) {
            InputValidator.checkStringLength(tabId, "标签页ID", 50);
        }
        
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPath = privateKeyPath;
        this.tabId = tabId;
    }

    // 重载构造函数，接受ConnectionParams对象
    public SSHConnectionManager(ConnectionParams params) {
        this(params.getHost(), params.getPort(), params.getUsername(), params.getDecryptedPassword(), params.getPrivateKeyPath());
        log.debug("使用ConnectionParams对象创建SSHConnectionManager，主机: {}，用户名: {}", 
                  params.getHost(), params.getUsername());

    }

    // 重载构造函数，接受ConnectionParams对象和tabId
    public SSHConnectionManager(ConnectionParams params, String tabId) {
        this(params.getHost(), params.getPort(), params.getUsername(), params.getDecryptedPassword(), params.getPrivateKeyPath(), tabId);
        log.debug("使用ConnectionParams对象和tabId创建SSHConnectionManager，主机: {}，用户名: {}，tabId: {}", 
                  params.getHost(), params.getUsername(), tabId);

    }

    private static void configureHostKeyChecking(JSch jsch, Properties config) throws JSchException {
        Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        try {
            Files.createDirectories(knownHosts.getParent());
            if (!Files.exists(knownHosts)) {
                Files.createFile(knownHosts);
            }
        } catch (IOException e) {
            throw new JSchException("无法创建 SSH known_hosts 文件: " + knownHosts, e);
        }
        jsch.setKnownHosts(knownHosts.toString());
        // 与 OpenSSH 默认行为一致：未知主机先核对指纹，再写入 known_hosts。
        config.put("StrictHostKeyChecking", "ask");
    }

    public boolean connect() throws JSchException, AIShellException {
        intentionalDisconnect = false;
        autoReconnectEnabled = SSH_PREFS.getBoolean("ssh.auto.reconnect", true);
        int connectTimeoutMs = Math.max(1, SSH_PREFS.getInt("ssh.timeout.connect", 30)) * 1000;
        int keepAliveMs = Math.max(0, SSH_PREFS.getInt("ssh.keepalive.interval", 30)) * 1000;
        // 默认值与设置页复选框保持一致(false)：此前默认 true 导致设置页显示"未启用"但实际启用压缩（2026-10-23 审计 P1-27）
        boolean compressionEnabled = SSH_PREFS.getBoolean("ssh.compression", false);
        try {
            JSch jsch = new JSch();

            // 如果提供了私钥路径，则使用密钥认证
            if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
                File keyFile = new File(privateKeyPath);
                if (!keyFile.exists()) {
                    log.error("Private key file does not exist: {}", privateKeyPath);
                    throw new AIShellException("PRIVATE_KEY_FILE_NOT_FOUND",
                            String.format("私钥文件不存在: %s", privateKeyPath));
                }
                
                // 检查私钥文件权限（对于Unix-like系统）
                if (!keyFile.canRead()) {
                    log.error("Private key file is not readable: {}", privateKeyPath);
                    throw new AIShellException("PRIVATE_KEY_FILE_PERMISSION_DENIED",
                            String.format("私钥文件无法读取，请检查文件权限: %s", privateKeyPath));
                }
                
                // 验证私钥格式
                try {
                    jsch.addIdentity(privateKeyPath, (byte[]) null);
                    log.info("Successfully loaded private key: {}", privateKeyPath);
                } catch (Exception e) {
                    log.error("Failed to load private key: {} - {}", privateKeyPath, e.getMessage());
                    throw new AIShellException("INVALID_PRIVATE_KEY_FORMAT",
                            String.format("无效的私钥格式: %s", e.getMessage()), e);
                }
            }

            SSHJumpHostConfig jump = SSHJumpHostConfig.load();
            jump.validate();
            String connectionHost = host;
            int connectionPort = port;
            if (jump.enabled()) {
                JSch jumpJsch = new JSch();
                if (jump.privateKeyPath() != null && !jump.privateKeyPath().isBlank()) {
                    jumpJsch.addIdentity(jump.privateKeyPath(), (byte[]) null);
                }
                Properties jumpConfig = new Properties();
                configureHostKeyChecking(jumpJsch, jumpConfig);
                jumpConfig.put("PreferredAuthentications",
                        jump.privateKeyPath() == null || jump.privateKeyPath().isBlank()
                                ? "password,publickey" : "publickey,password");
                jumpSession = jumpJsch.getSession(jump.username(), jump.host(), jump.port());
                jumpSession.setUserInfo(new FxHostKeyUserInfo(jump.password()));
                if (jump.password() != null && !jump.password().isBlank()) {
                    jumpSession.setPassword(jump.password().getBytes(StandardCharsets.UTF_8));
                }
                SSHProxyConfig.load().apply(jumpSession);
                jumpSession.setConfig(jumpConfig);
                jumpSession.setServerAliveInterval(keepAliveMs);
                jumpSession.setServerAliveCountMax(AppConstants.SERVER_ALIVE_COUNT_MAX);
                log.info("Connecting through jump host {}:{} user: {}", jump.host(), jump.port(), jump.username());
                jumpSession.connect(connectTimeoutMs);
                jumpForwardedPort = jumpSession.setPortForwardingL("127.0.0.1", 0, host, port);
                connectionHost = "127.0.0.1";
                connectionPort = jumpForwardedPort;
            }

            session = jsch.getSession(username, connectionHost, connectionPort);
            session.setUserInfo(new FxHostKeyUserInfo(password));
            if (!jump.enabled()) SSHProxyConfig.load().apply(session);

            // 如果有密码，则设置密码
            if (password != null && !password.isEmpty()) {

                session.setPassword(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            log.info("Connecting to {}:{} user: {}", host, port, username);
            Properties config = new Properties();
            configureHostKeyChecking(jsch, config);
            if (jump.enabled()) {
                config.put("HostKeyAlias", port == 22 ? host : "[" + host + "]:" + port);
            }
            // 根据认证方式设置首选认证方法
            if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
                config.put("PreferredAuthentications", "publickey,password");
            } else {
                config.put("PreferredAuthentications", "password,publickey");
            }
            // 添加更多配置选项以提高连接稳定性
            config.put("ConnectTimeout", String.valueOf(AppConstants.CONNECT_TIMEOUT_MS)); // 连接超时
            config.put("ServerAliveInterval", String.valueOf(keepAliveMs)); // 保持连接的服务器心跳间隔
            config.put("ServerAliveCountMax", String.valueOf(AppConstants.SERVER_ALIVE_COUNT_MAX)); // 服务器心跳最大尝试次数
            config.put("Compression", compressionEnabled ? "yes" : "no");
            config.put("CompressionLevel", "9"); // 设置压缩级别
            session.setConfig(config);
            session.setServerAliveInterval(keepAliveMs);
            session.setServerAliveCountMax(AppConstants.SERVER_ALIVE_COUNT_MAX);

            // 实现重试机制
            int retryCount = 0;
            Exception lastException = null;

            while (retryCount < AppConstants.MAX_RETRIES) {
                try {
                    log.info("Attempting to connect to {}:{} (attempt {}/{})", host, port, retryCount + 1, AppConstants.MAX_RETRIES);
                    session.connect(connectTimeoutMs); // 设置会话连接超时

                    if (session.isConnected()) {
                        log.info("Successfully connected to {}:{}", host, port);

                        // 连接成功后立即启动持久 Shell（如果有预注册回调则使用）
                        try {
                            startInteractiveShellWithCallback();
                            log.info("Interactive shell started successfully");
                            
                            // 执行测试命令验证连接有效性
                            String testResult = executeCommand("echo 'Connection Test Successful'");
                            if (testResult != null && testResult.contains("Connection Test Successful")) {
                                log.info("Connection validation successful for {}: {}", host, port);
                            } else {
                                log.warn("Connection validation failed for {}: {}", host, port);
                                // 即使验证失败也继续，因为连接已建立
                            }
                        } catch (JSchException | IOException | AIShellException e) {
                            log.error("Failed to start interactive shell: {}", e.getMessage(), e);
                            disconnect();
                            return false; // 连接失败，因为无法启动shell
                        }

                        // 通知连接状态监听器连接成功
                        notifyConnectionListeners(true);
                        
                        return true;
                    }
                } catch (JSchException e) {
                    lastException = e;
                    log.error("Connection attempt {} failed: {}", retryCount + 1, e.getMessage());

                    // 特别处理连接被拒绝的情况
                    if (e.getCause() instanceof java.net.ConnectException) {
                        String causeMessage = e.getCause().getMessage();
                        if (causeMessage != null && causeMessage.toLowerCase().contains("connection refused")) {
                            log.error("Connection refused: The SSH server at {}:{} may not be running or is blocking connections.", host, port);
                            log.error("Please verify:");
                            log.error("1. The SSH server is running on the target machine");
                            log.error("2. The host address '{}' and port {} are correct", host, port);
                            log.error("3. The port {} is not blocked by firewall", port);
                            log.error("4. The SSH service is configured to accept connections");
                        }
                    }
                    // 处理认证失败的情况
                    else if (isAuthenticationError(e)) {
                        log.error("Authentication failed for user '{}' on {}:{}, please check username and password/key", 
                                  username, host, port);
                        // 认证信息不变时重复尝试没有意义，应立即交给 UI 请求新密码。
                        break;
                    }
                    // 处理主机密钥验证失败的情况
                    else if (e.getMessage().toLowerCase().contains("unknown cipher")) {
                        log.warn("Cipher negotiation failed, may need to adjust SSH client configuration");
                    }

                    // 如果不是最后一次尝试，稍等后重试
                    if (retryCount < AppConstants.MAX_RETRIES - 1) {
                        log.info("Retrying in {} ms...", AppConstants.RETRY_DELAY_MS);
                        try {
                            Thread.sleep(AppConstants.RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                retryCount++;
            }

            // 如果所有重试都失败
            if (lastException != null) {
                log.error("SSH connection failed after {} retries: {}", AppConstants.MAX_RETRIES, lastException.getMessage(), lastException);

                // 如果是连接被拒绝的异常，提供更详细的错误信息
                if (lastException.getCause() instanceof java.net.ConnectException) {
                    String causeMessage = lastException.getCause().getMessage();
                    if (causeMessage != null && causeMessage.toLowerCase().contains("connection refused")) {
                        throw new AIShellException("SSH_CONNECTION_REFUSED",
                                String.format("无法连接到SSH服务器 %s:%d。连接被拒绝，请检查SSH服务器是否正在运行，以及防火墙设置。", host, port),
                                lastException);
                    }
                }
                // 如果是认证失败
                else if (isAuthenticationError(lastException)) {
                    throw new AIShellException("SSH_AUTHENTICATION_FAILED",
                            String.format("SSH认证失败，用户名或密码/密钥错误: %s@%s:%d", username, host, port),
                            lastException);
                }
            }
            disconnect();
            return false;
        } catch (AIShellException e) {
            // 重新抛出已包装的AIShell异常
            disconnect();
            notifyConnectionListeners(false);
            throw e;
        } catch (Exception e) {
            log.error("SSH connection error: {}", e.getMessage(), e);
            disconnect();
            
            // 通知连接状态监听器连接失败
            notifyConnectionListeners(false);
            
            // 提供更友好的错误信息
            throw new AIShellException("SSH_CONNECTION_ERROR",
                    String.format("SSH连接错误: %s。请检查网络连接、主机地址、端口和认证信息。", e.getMessage()),
                    e);
        }
    }

    public void disconnect() {
        log.info("Disconnecting SSH connection to {}:{}", host, port);
        intentionalDisconnect = true;
        // 用户主动断开（含关闭标签）时，取消可能正在进行的自动重连，避免会话在标签关闭后复活成孤儿连接。
        if (reconnectThread != null && reconnectThread.isAlive() && Thread.currentThread() != reconnectThread) {
            reconnectCancelled = true;
            reconnectThread.interrupt();
        }

        // 首先停止读取线程
        stopReaderThread();

        // 安全关闭shell writer
        if (shellWriter != null) {
            try {
                shellWriter.flush();
                shellWriter.close();
            } catch (Exception e) {
                log.warn("Error closing shell writer: {}", e.getMessage());
            }
        }

        // 安全断开shell通道
        if (shellChannel != null && shellChannel.isConnected()) {
            try {
                shellChannel.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting shell channel: {}", e.getMessage());
            }
        }

        // 安全断开exec通道
        if (execChannel != null && execChannel.isConnected()) {
            try {
                execChannel.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting exec channel: {}", e.getMessage());
            }
        }

        // 安全断开会话
        dynamicForwarders.values().forEach(DynamicSocksForwarder::close);
        dynamicForwarders.clear();
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting session: {}", e.getMessage());
            }
        }

        if (jumpSession != null) {
            try {
                if (jumpForwardedPort > 0 && jumpSession.isConnected()) {
                    jumpSession.delPortForwardingL(jumpForwardedPort);
                }
            } catch (Exception e) {
                log.warn("Error closing jump-host forwarding: {}", e.getMessage());
            } finally {
                jumpForwardedPort = -1;
                if (jumpSession.isConnected()) jumpSession.disconnect();
                jumpSession = null;
            }
        }

        log.info("SSH connection to {}:{} disconnected successfully", host, port);
    }

    /**
     * Releases the legacy RichTextFX shell while keeping the authenticated SSH session alive.
     * A terminal emulator can then open its own PTY channel on the same session.
     */
    public synchronized void releaseLegacyInteractiveShell() {
        stopReaderThread();
        if (shellWriter != null) {
            shellWriter.close();
            shellWriter = null;
        }
        if (shellChannel != null) {
            shellChannel.disconnect();
            shellChannel = null;
        }
        shellInputStream = null;
        shellOutputStream = null;
        log.debug("Legacy interactive shell released for tab: {}", tabId);
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    /**
     * 启动持久 Shell 会话（在 connect() 成功后调用）
     */
    public void startInteractiveShell() throws JSchException, IOException {
        startInteractiveShellWithCallback();
    }
    
    /**
     * 启动持久 Shell 会话，支持预注册回调
     */
    public void startInteractiveShellWithCallback() throws JSchException, IOException {
        if (!isConnected()) {
            throw new IllegalStateException("Not connected");
        }
    
        if (shellChannel != null && shellChannel.isConnected()) {
            shellChannel.disconnect();
        }
        stopReaderThread();  // 先停止旧线程
    
        shellChannel = (ChannelShell) session.openChannel("shell");
    
        // 设置 Pty，启用 ANSI 颜色支持，指定终端类型为 xterm-256color
        shellChannel.setPty(true);
        // 设置终端类型支持 ANSI 颜色
        Properties config = new Properties();
        config.put("TerminalType", "xterm-256color");
        shellChannel.setEnv("TERM", "xterm-256color");
        shellChannel.setPtyType("xterm-256color");
    
        shellInputStream = shellChannel.getInputStream();
        shellOutputStream = shellChannel.getOutputStream();
    
        shellChannel.connect();
    
        shellWriter = new PrintWriter(shellOutputStream, true);
    
        running.set(true);
        readerThread = new Thread(this::readOutputLoop, "SSH-Shell-Reader");
        readerThread.setDaemon(true); // 设置为守护线程
        readerThread.start();
    }

    // 添加输出回调接口
    public interface ShellOutputCallback {
        void onOutput(String output, String color);
    }

    public void addOutputCallback(ShellOutputCallback callback) {
        synchronized (outputCallbacks) {
            outputCallbacks.add(callback);
            log.info("Added output callback to connection {}. Total callbacks: {}",
                    tabId != null ? tabId : "unknown", outputCallbacks.size());
            // 添加额外的调试信息
            log.debug("Current thread: {}, Callback hashcode: {}", Thread.currentThread().getName(), callback.hashCode());

            // 如果已经有shell会话在运行，确保它知道有新的回调
            if (shellChannel != null && shellChannel.isConnected()) {
                log.debug("Shell channel already connected, new callback will receive future output");
            }
        }
    }

    public void removeOutputCallback(ShellOutputCallback callback) {
        synchronized (outputCallbacks) {
            boolean removed = outputCallbacks.remove(callback);
            log.info("Removed output callback from connection {}: {}",
                    tabId != null ? tabId : "unknown", removed);
            log.info("Remaining callbacks count: {}", outputCallbacks.size());
        }
    }

    /**
     * 通过shell channel发送交互式命令
     * 用于发送用户输入的命令并实时显示输出
     */
    public void sendCommand(String command) {
        // 命令可能携带凭据（如 mysql -pSecret），只在 DEBUG 记录，避免密码进常规日志
        log.debug("Sending command from thread: {}, Thread ID: {}", Thread.currentThread().getName(), Thread.currentThread().threadId());
        if (!isConnected() || shellChannel == null || !shellChannel.isConnected() || shellWriter == null) {
            throw new IllegalStateException("SSH interactive shell is not connected");
        }
        shellWriter.println(command);
        shellWriter.flush();
        if (shellWriter.checkError()) {
            throw new IllegalStateException("Failed to write command to SSH interactive shell");
        }
        lastSentCommand = command.trim();  // 更新
        // 设置标志，表示我们期待下一个命令回显
        expectingCommandEcho.set(true);
    }

    /**
     * 发送中断信号（Ctrl+C）到远程服务器
     */
    public void sendInterruptSignal() {
        log.info("Sending interrupt signal (Ctrl+C) to remote server");
        if (shellOutputStream != null) {
            try {
                // 发送中断信号 (ASCII 3, ETX - End of Transmission Character)
                shellOutputStream.write(3);  // ASCII 3 is Ctrl+C
                shellOutputStream.flush();
                log.debug("Interrupt signal sent successfully");
            } catch (IOException e) {
                log.error("Error sending interrupt signal: {}", e.getMessage(), e);
            }
        } else {
            log.warn("Shell output stream is null, cannot send interrupt signal");
        }
        // 重置命令回显标志
        expectingCommandEcho.set(false);
    }

    /**
     * 发送特殊控制字符到远程服务器
     * @param controlChar 控制字符，如3(Ctrl+C), 4(Ctrl+D), 26(Ctrl+Z)
     */
    public void sendControlCharacter(int controlChar) {
        log.info("Sending control character: {} to remote server", controlChar);
        if (shellOutputStream != null) {
            try {
                shellOutputStream.write(controlChar);
                shellOutputStream.flush();
                log.debug("Control character {} sent successfully", controlChar);
            } catch (IOException e) {
                log.error("Error sending control character {}: {}", controlChar, e.getMessage(), e);
            }
        } else {
            log.warn("Shell output stream is null, cannot send control character {}", controlChar);
        }
        // 重置命令回显标志
        expectingCommandEcho.set(false);
    }

    /**
     * 使用ChannelExec同步执行命令并返回结果
     * 用于获取系统信息等一次性命令
     */
    public String executeCommand(String command) throws AIShellException {
        // 命令原文可能含凭据（-pSecret、-u user:pass 等），降级为 DEBUG 避免密码进日志
        log.debug("Executing command");
        if (!isConnected()) {
            throw new AIShellException("SSH_CONNECTION_NOT_ESTABLISHED", "SSH connection is not established");
        }

        ChannelExec channelExec = null;
        InputStream in = null;
        try {
            channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(command);

            // 收集 stderr 而不是把它回灌进 stdin：
            // setExtOutputStream(getOutputStream()) 会把远程错误输出写回命令的 stdin，
            // 轻则错误信息丢失，重则因 channel 窗口耗尽阻塞 Session IO 线程、冻死整个会话（2026-10-23 审计 P0-3）
            java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
            channelExec.setErrStream(stderr);

            in = channelExec.getInputStream();
            channelExec.connect(AppConstants.EXEC_CHANNEL_TIMEOUT);

            // 轮询读取并在无数据时检查截止时间：in.read() 本身会阻塞，
            // 只在读到数据后才检查超时会让无输出的挂起命令永久阻塞调用线程（2026-10-23 审计 P1）
            StringBuilder output = new StringBuilder();
            byte[] buffer = new byte[AppConstants.COMMAND_BUFFER_SIZE];
            long deadline = System.currentTimeMillis() + AppConstants.EXEC_CHANNEL_TIMEOUT;
            boolean channelClosed = false;
            while (true) {
                int available = in.available();
                if (available > 0) {
                    int length = in.read(buffer, 0, Math.min(buffer.length, available));
                    if (length < 0) { channelClosed = true; break; }
                    output.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                } else if (channelClosed || channelExec.isClosed()) {
                    // 通道关闭后再排空一次缓冲
                    int length = in.read(buffer);
                    if (length == -1) break;
                    output.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                    channelClosed = true;
                } else {
                    if (System.currentTimeMillis() > deadline) {
                        log.warn("Command execution timed out");
                        break;
                    }
                    Thread.sleep(25);
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Command execution interrupted");
            }

            String err = stderr.toString(StandardCharsets.UTF_8);
            String result = output.toString().trim();
            if (!err.isBlank()) {
                result = (result.isBlank() ? "" : result + "\n") + err.trim();
            }
            log.debug("Command result length: {}", result.length());
            return result;
        } catch (Exception e) {
            // 命令可能内嵌凭据，错误日志不记录命令原文（2026-10-23 审计 P0-5）
            log.error("Error executing command: {}", e.getMessage(), e);
            throw new AIShellException("COMMAND_EXECUTION_FAILED", "Error executing command: " + e.getMessage(), e);
        } finally {
            // 确保资源被释放
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    log.warn("Error closing input stream: {}", e.getMessage());
                }
            }
            if (channelExec != null && channelExec.isConnected()) {
                channelExec.disconnect();
            }
        }
    }

    /** Bounded non-interactive execution used exclusively by Agent automation. */
    public String executeAgentCommand(String command, long timeoutMillis, int maxOutputBytes) throws AIShellException {
        if (!isConnected()) throw new AIShellException("SSH_CONNECTION_NOT_ESTABLISHED", "SSH connection is not established");
        if (timeoutMillis <= 0 || maxOutputBytes <= 0) throw new IllegalArgumentException("Invalid execution limits");
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
            channel.setErrStream(stderr);
            InputStream stdout = channel.getInputStream();
            channel.connect((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Agent command cancelled");
                while (stdout.available() > 0) {
                    int read = stdout.read(buffer, 0, Math.min(buffer.length, stdout.available()));
                    if (read < 0) break;
                    if (result.size() + stderr.size() + read > maxOutputBytes) {
                        throw new AIShellException("AGENT_OUTPUT_LIMIT", "Agent command output exceeded " + maxOutputBytes + " bytes");
                    }
                    result.write(buffer, 0, read);
                }
                if (stderr.size() + result.size() > maxOutputBytes) {
                    throw new AIShellException("AGENT_OUTPUT_LIMIT", "Agent command output exceeded " + maxOutputBytes + " bytes");
                }
                if (channel.isClosed() && stdout.available() == 0) break;
                if (System.currentTimeMillis() >= deadline) {
                    throw new AIShellException("AGENT_COMMAND_TIMEOUT", "Agent command timed out after " + timeoutMillis + "ms");
                }
                Thread.sleep(25);
            }
            String out = result.toString(StandardCharsets.UTF_8);
            String err = stderr.toString(StandardCharsets.UTF_8);
            return (out + (err.isBlank() ? "" : (out.isBlank() ? "" : "\n") + err)).trim();
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (AIShellException e) {
            throw e;
        } catch (Exception e) {
            throw new AIShellException("AGENT_COMMAND_FAILED", "Agent command failed: " + e.getMessage(), e);
        } finally {
            if (channel != null) channel.disconnect();
        }
    }

    /**
     * 获取连接成功后的默认系统信息
     * 通过发送命令到shell获取系统信息
     */
    public String getSystemInfo() throws AIShellException {
        StringBuilder systemInfo = new StringBuilder();

        // 获取系统基本信息
        // 直接返回主机名，避免echo命令产生额外输出
        String hostname = executeCommand("hostname").trim();
        systemInfo.append("Welcome to ").append(hostname).append("\n");

        // 获取最后一次登录信息
        String lastLoginInfo = executeCommand("last -n 1").trim();
        if (!lastLoginInfo.isEmpty()) {
            systemInfo.append("\n").append(lastLoginInfo).append("\n");
        }

        // 添加提示符
        systemInfo.append(shellPrompt);

        return systemInfo.toString();
    }

    /**
     * 检查是否应该过滤原始输出行
     */
    private boolean shouldFilterRawOutput(String line) {
        // 只过滤掉我们程序主动发送的echo命令输出，保留SSH服务器端的登录信息
        return line.contains("echo \"Welcome to $(hostname)") ||
                line.contains("echo '\\\\n'") ||
                line.contains("echo '\\\\nLast login:");
    }

    private void readOutputLoop() {
        try (InputStream in = shellInputStream) {
            byte[] buffer = new byte[AppConstants.OUTPUT_BUFFER_SIZE];  // 增大缓冲区，减少读次数
            StringBuilder pending = new StringBuilder();
            long lastNotifyTime = System.currentTimeMillis();  // 用于超时通知

            while (running.get() && !Thread.interrupted()) {
                // 先检查可用数据，避免阻塞
                int available = in.available();
                if (available == 0) {
                    try {
                        Thread.sleep(AppConstants.READER_THREAD_SLEEP_MS);  // 短暂休眠，避免 CPU 忙等
                    } catch (InterruptedException e) {
                        // 当线程被中断时，恢复中断状态并退出循环
                        Thread.currentThread().interrupt();
                        log.debug("Reader thread interrupted, exiting loop");
                        break;
                    }

                    // 超时检查：如果 pending 有内容但长时间无新数据，强制通知（防卡死）
                    if (!pending.isEmpty() && (System.currentTimeMillis() - lastNotifyTime) > AppConstants.TIMEOUT_NOTIFY_THRESHOLD_MS) {
                        String partial = pending.toString();
                        pending.setLength(0);  // 清空 pending
                        notifyOutputCallbacks(partial);
                        lastNotifyTime = System.currentTimeMillis();
                    }
                    continue;
                }

                int len = in.read(buffer, 0, Math.min(available, buffer.length));
                if (len == -1) break;

                String text = new String(buffer, 0, len, StandardCharsets.UTF_8);  // 指定编码防乱码
                pending.append(text);
                lastNotifyTime = System.currentTimeMillis();  // 更新最后通知时间

                log.debug("Read {} bytes, pending length now: {}", len, pending.length());
                // 关键修复：循环处理 pending 中的所有完整行
                String pendingStr = pending.toString();
                int lastNewline = 0;
                int maxIterations = 1000; // 防止无限循环的安全措施
                int iterationCount = 0;
                
                while (iterationCount < maxIterations) {
                    int newlineIndex = pendingStr.indexOf('\n', lastNewline);
                    if (newlineIndex == -1) {
                        // 没有找到换行符，保留剩余内容等待下一次读取
                        break;
                    }

                    // 提取完整行（包括 \n）
                    String completeLine = pendingStr.substring(lastNewline, newlineIndex + 1);
                    notifyOutputCallbacks(completeLine);

                    lastNewline = newlineIndex + 1;
                    iterationCount++;
                    
                    // 防止字符串索引越界
                    if (lastNewline >= pendingStr.length()) {
                        break;
                    }
                }
                
                // 如果达到最大迭代次数，记录警告
                if (iterationCount >= maxIterations) {
                    log.warn("SSH输出处理达到最大迭代次数限制: {}", maxIterations);
                }

                // 删除已通知的部分，保留残留
                if (lastNewline > 0) {
                    pending.delete(0, lastNewline);
                }

                // 添加超时机制：如果 pending > 10KB，无条件通知（防极端累积）
                if (pending.length() > AppConstants.MAX_PENDING_OUTPUT_SIZE) {  // 10KB
                    String partial = pending.toString();
                    pending.setLength(0);  // 清空 pending
                    notifyOutputCallbacks(partial);
                    lastNotifyTime = System.currentTimeMillis();
                }

                // 添加更积极的超时机制：即使没有换行符，如果数据停留超过一定时间也发送
                if (pending.length() > 0 && (System.currentTimeMillis() - lastNotifyTime) > AppConstants.TIMEOUT_NOTIFY_THRESHOLD_MS / 2) {
                    String partial = pending.toString();
                    pending.setLength(0);  // 清空 pending
                    if (!partial.isEmpty()) {
                        notifyOutputCallbacks(partial);
                        lastNotifyTime = System.currentTimeMillis();
                    }
                }
            }

            // 在退出循环后，确保剩余内容被发送
            if (!pending.isEmpty()) {
                notifyOutputCallbacks(pending.toString());
            }
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                log.error("Shell reader error", e);
                try {
                    // 如果在非UI线程中，尝试使用Platform.runLater
                    Platform.runLater(() -> {
                        notifyOutputCallbacks("\n[ERROR: Connection lost - " + e.getMessage() + "]\n");
                    });
                } catch (Exception platformEx) {
                    log.error("Error in Platform.runLater, calling directly", platformEx);
                    // 如果Platform.runLater失败，直接调用通知
                    notifyOutputCallbacks("\n[ERROR: Connection lost - " + e.getMessage() + "]\n");
                }
                
                // 触发自动重连机制
                if (autoReconnectEnabled && !intentionalDisconnect && !reconnecting) {
                    startReconnectProcess();
                }
            }
        } finally {
            // 确保running状态被正确设置为false
            running.set(false);
            
            // 如果连接断开且启用了自动重连，则尝试重连
            if (autoReconnectEnabled && !intentionalDisconnect && !reconnecting && !isConnected()) {
                log.info("Detected connection loss, attempting auto-reconnect for tab: {}", tabId);
                startReconnectProcess();
            }
        }
    }

    private void notifyOutputCallbacks(String output) {
        // 不过滤任何内容，直接传递原始输出
        // 命令回显和提示符由 SSH 服务器处理，客户端显示所有内容
        if (!output.trim().isEmpty()) {
            String color = "default";
            synchronized (outputCallbacks) {
                if (outputCallbacks.isEmpty()) {
                    log.warn("No output callbacks registered for connection {}, but received output: {}",
                            tabId != null ? tabId : "unknown", output);
                }

                for (ShellOutputCallback callback : outputCallbacks) {
                    if (callback != null) {
                        callback.onOutput(output, color);
                    }
                }
            }
        }
    }

    private void stopReaderThread() {
        running.set(false);
        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
            try {
                readerThread.join(AppConstants.READER_THREAD_JOIN_TIMEOUT);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        readerThread = null;
    }

    /**
     * 检查连接健康状况
     */
    public void checkConnectionHealth() {
        if (session != null && session.isConnected()) {
            try {
                // 发送一个简单的命令来测试连接
                String result = executeCommand("echo 'alive'");
                log.debug("Connection health check result: {}", result);
            } catch (Exception e) {
                log.warn("Connection health check failed: {}", e.getMessage());
                // 连接可能有问题，尝试重新连接
                if (autoReconnectEnabled && !reconnecting) {
                    startReconnectProcess();
                }
            }
        }
    }

    /**
     * 检查连接是否有效
     */
    public boolean isConnectionValid() {
        return session != null && session.isConnected();
    }

    /**
     * 开始重连过程
     */
    private void startReconnectProcess() {
        if (!autoReconnectEnabled || intentionalDisconnect || reconnecting) {
            return;
        }

        reconnecting = true;
        reconnectCancelled = false;
        reconnectThread = new Thread(() -> {
            int attempts = 0;
            while (attempts < maxReconnectAttempts) {
                // 每次尝试前都检查是否已被用户断开（关闭标签等）取消
                if (reconnectCancelled || intentionalDisconnect) {
                    break;
                }
                try {
                    log.info("Attempting to reconnect... (attempt {}/{})", attempts + 1, maxReconnectAttempts);

                    // 重连自身的 disconnect() 与 connect() 失败路径都会把 intentionalDisconnect 置位；
                    // 用户取消由 reconnectCancelled 表达，每轮迭代前清理该标志，
                    // 否则"最多重连 N 次"实际只执行 1 次（2026-10-23 审计 P1-3）
                    intentionalDisconnect = false;

                    // 断开现有连接（如果存在）
                    if (session != null && session.isConnected()) {
                        session.disconnect();
                    }

                    // 确保所有资源都被清理
                    disconnect();

                    // 尝试重新连接
                    if (connect()) {
                        if (reconnectCancelled) {
                            // 重连完成前用户已关闭标签：丢弃复活的会话，避免孤儿连接
                            log.info("Reconnection succeeded for tab {} but it was cancelled; discarding the session", tabId);
                            disconnect();
                            break;
                        }
                        log.info("Reconnection successful");
                        reconnecting = false;
                        reconnectAttempts = 0;

                        // 通知连接状态监听器
                        notifyConnectionListeners(true);
                        return;
                    }
                } catch (Exception e) {
                    log.error("Reconnection attempt {} failed: {}", attempts + 1, e.getMessage());
                }

                attempts++;
                reconnectAttempts = attempts;

                if (attempts < maxReconnectAttempts) {
                    try {
                        Thread.sleep(reconnectDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            reconnecting = false;
            // intentionalDisconnect 会被重连自身的断开操作置位，只有 reconnectCancelled 代表用户取消
            if (reconnectCancelled) {
                log.info("Reconnection cancelled for tab {}", tabId);
            } else {
                log.error("Maximum reconnection attempts reached. Reconnection failed.");
                // 通知连接状态监听器连接失败
                notifyConnectionListeners(false);
            }
        });

        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private boolean isAuthenticationError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("auth fail") || normalized.contains("authentication failed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 通知连接状态监听器
     */
    private void notifyConnectionListeners(boolean connected) {
        for (ConnectionStateListener listener : connectionStateListeners) {
            try {
                if (connected) {
                    listener.onConnected(tabId);
                } else {
                    listener.onDisconnected(tabId);
                }
            } catch (Exception e) {
                log.error("Error notifying connection state listener: {}", e.getMessage());
            }
        }
    }

    // 添加一个公共方法来获取回调数量，用于调试
    public int getOutputCallbacksSize() {
        synchronized (outputCallbacks) {
            return outputCallbacks.size();
        }
    }

    // 添加一个公共方法来获取回调集合，用于调试
    public Set<ShellOutputCallback> getOutputCallbacks() {
        synchronized (outputCallbacks) {
            return new HashSet<>(outputCallbacks);
        }
    }

    @Override
    public void close() {
        disconnect();
    }
    
    /**
     * 获取连接的标签页ID
     */
    public String getTabId() {
        return tabId;
    }
    
    /**
     * 获取重连尝试次数
     */
    public int getReconnectAttempts() {
        return reconnectAttempts;
    }
    
    /**
     * 更新当前用户信息
     */
    public void setCurrentUser(String user) {
        this.currentUser = user;
    }
    
    /**
     * 获取当前用户
     */
    public String getCurrentUser() {
        return this.currentUser;
    }

    /**
     * 统一的连接显示名构造。供连接列表、编辑对话框、测试连接等共用，
     * 避免各处重复拼接 "host:port - username" 造成不一致
     */
    public static String buildDisplayName(com.runzhi.aishell.ssh.domain.ConnectionParams params) {
        if (params == null) {
            return "";
        }
        String host = params.getHost() == null ? "" : params.getHost();
        int port = params.getPort();
        String user = params.getUsername() == null ? "" : params.getUsername();
        return host + ":" + port + " - " + user;
    }
    
    /**
     * 更新当前主机信息
     */
    public void setCurrentHost(String host) {
        this.currentHost = host;
    }
    
    /**
     * 获取当前主机
     */
    public String getCurrentHost() {
        return this.currentHost;
    }
    
    /**
     * 更新当前路径
     */
    public void setCurrentPath(String path) {
        this.currentPath = path;
    }
    
    /**
     * 获取当前路径
     */
    public String getCurrentPath() {
        return this.currentPath;
    }
    
    /**
     * 根据当前路径和用户信息生成提示符
     */
    public String generatePrompt() {
        String promptSymbol = "root".equals(this.currentUser) ? "#" : "$";
        return "[" + this.currentUser + "@" + this.currentHost + " " + this.currentPath + "]" + promptSymbol + " ";
    }
    
    /**
     * 生成PowerShell风格的提示符
     */
    public String generatePowerShellPrompt() {
        return this.currentUser + "@" + this.currentHost + ":" + this.currentPath + "> ";
    }
    
    /**
     * 生成简单的提示符
     */
    public String generateSimplePrompt() {
        return this.currentPath + " $ ";
    }
    
    /**
     * 添加连接状态监听器
     */
    public void addConnectionStateListener(ConnectionStateListener listener) {
        if (!connectionStateListeners.contains(listener)) {
            connectionStateListeners.add(listener);
            log.info("Added connection state listener: {}", listener.getClass().getSimpleName());
        }
    }
    
    /**
     * 移除连接状态监听器
     */
    public void removeConnectionStateListener(ConnectionStateListener listener) {
        boolean removed = connectionStateListeners.remove(listener);
        if (removed) {
            log.info("Removed connection state listener: {}", listener.getClass().getSimpleName());
        }
    }

    public synchronized int openLocalPortForward(String bindAddress, int localPort,
                                                 String remoteHost, int remotePort) throws JSchException {
        requireConnectedSession();
        validateForwardPort(localPort, true);
        validateForwardPort(remotePort, false);
        if (remoteHost == null || remoteHost.isBlank()) throw new IllegalArgumentException("远程主机不能为空");
        String bind = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress;
        return session.setPortForwardingL(bind, localPort, remoteHost, remotePort);
    }

    public synchronized void closeLocalPortForward(String bindAddress, int localPort) throws JSchException {
        requireConnectedSession();
        validateForwardPort(localPort, false);
        String bind = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress;
        session.delPortForwardingL(bind, localPort);
    }

    public synchronized void openRemotePortForward(int remotePort, String localHost, int localPort)
            throws JSchException {
        requireConnectedSession();
        validateForwardPort(remotePort, false);
        validateForwardPort(localPort, false);
        if (localHost == null || localHost.isBlank()) throw new IllegalArgumentException("本地主机不能为空");
        session.setPortForwardingR(remotePort, localHost, localPort);
    }

    public synchronized void closeRemotePortForward(int remotePort) throws JSchException {
        requireConnectedSession();
        validateForwardPort(remotePort, false);
        session.delPortForwardingR(remotePort);
    }

    public synchronized int openDynamicPortForward(String bindAddress, int localPort) throws IOException {
        requireConnectedSession();
        validateForwardPort(localPort, true);
        String bind = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress;
        DynamicSocksForwarder forwarder = new DynamicSocksForwarder(session, bind, localPort);
        int actualPort = forwarder.port();
        String key = bind + ":" + actualPort;
        DynamicSocksForwarder existing = dynamicForwarders.putIfAbsent(key, forwarder);
        if (existing != null) {
            forwarder.close();
            throw new IllegalStateException("动态转发已存在: " + key);
        }
        return actualPort;
    }

    public synchronized void closeDynamicPortForward(String bindAddress, int localPort) {
        requireConnectedSession();
        validateForwardPort(localPort, false);
        String bind = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress;
        DynamicSocksForwarder forwarder = dynamicForwarders.remove(bind + ":" + localPort);
        if (forwarder == null) throw new IllegalArgumentException("动态转发不存在");
        forwarder.close();
    }

    private void requireConnectedSession() {
        if (session == null || !session.isConnected()) throw new IllegalStateException("SSH 会话未连接");
    }

    private void validateForwardPort(int port, boolean allowAutomatic) {
        if ((allowAutomatic && port == 0) || (port >= 1 && port <= 65535)) return;
        throw new IllegalArgumentException("端口必须在 " + (allowAutomatic ? "0" : "1") + " 到 65535 之间");
    }
}