/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ssh.FileTransferService;
import com.runzhi.aishell.ssh.SSHService;
import lombok.extern.slf4j.Slf4j;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 外部编辑器生命周期（方案 §3.3 / §4.4 / §5.4）：
 *
 * <ol>
 *   <li>下载远端文件到会话隔离的临时目录，记录远端 mtime/size/SHA-256；</li>
 *   <li>ProcessBuilder 启动编辑器（参数列表，无 shell）；</li>
 *   <li>WatchService 监听临时目录（ENTRY_MODIFY / ENTRY_CREATE），500ms 防抖，
 *       覆盖 VS Code「先写临时文件再 rename」的原子保存；</li>
 *   <li>哈希比对变化后按 autoUpload 直接回传或弹确认；</li>
 *   <li>关闭会话或退出时清理临时文件，启动时清理超过保留期的残留。</li>
 * </ol>
 *
 * <p>进程退出不等于编辑结束（VS Code 默认复用窗口），以文件变更和显式「结束编辑」为准。
 */
@Slf4j
public final class ExternalEditorService {

    /** 外部编辑器回传的确认结果。 */
    public enum UploadDecision { UPLOAD, KEEP_LOCAL, DISCARD }

    /** UI 交互回调：确认框等必须在 FX 线程执行，由实现者保证。 */
    public interface Interaction {
        void notify(String title, String message);
        UploadDecision askUpload(String remotePath, String message);
        boolean isAutoUpload();
        void statusMessage(String message);

        /** 多个可用编辑器时让用户选择一个（返回 null 表示取消）。 */
        ExternalEditor chooseEditor(List<ExternalEditor> editors);
    }

    private static final String TEMP_ROOT_NAME = "aishell-edit";
    private static final long DEBOUNCE_MS = 500;
    private static final long SIZE_STABLE_TIMEOUT_MS = 2000;
    private static final long SIZE_STABLE_INTERVAL_MS = 250;
    private static final AtomicBoolean STARTUP_CLEANUP_DONE = new AtomicBoolean();

    private final SSHService sshService;
    private final String tabId;
    private final Interaction interaction;
    private final Path sessionRoot;

    private final Map<String, EditSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "editor-external-io"));
    // 监视循环独占线程：与 I/O 任务共用单线程会导致 openRemote 被排在 watchLoop 之后永远无法执行
    private final ExecutorService watchExecutor = Executors.newSingleThreadExecutor(
            r -> daemon(r, "editor-external-watchloop"));
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor(
            r -> daemon(r, "editor-external-watch"));
    private final Map<String, ScheduledFuture<?>> pendingChecks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile WatchService watchService;
    private volatile boolean watchFailed;

    public ExternalEditorService(SSHService sshService, String tabId, Interaction interaction) {
        this.sshService = sshService;
        this.tabId = tabId;
        this.interaction = interaction;
        this.sessionRoot = Path.of(System.getProperty("java.io.tmpdir"), TEMP_ROOT_NAME,
                tabId == null ? "none" : sanitize(tabId));
        if (STARTUP_CLEANUP_DONE.compareAndSet(false, true)) {
            cleanupStaleTempDirs();
        }
        startWatcher();
    }

    /** 用首选编辑器打开本地文件（本地文件就地保存，无需回传；无 shell 调用）。 */
    public static void openLocalFile(Path path) throws IOException {
        openLocalFile(path, ExternalEditorDetector.preferred());
    }

    /** 用指定编辑器打开本地文件。 */
    public static void openLocalFile(Path path, ExternalEditor editor) throws IOException {
        if (editor == null || editor.isSystemDefault()) {
            Desktop.getDesktop().open(path.toFile());
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(editor.buildCommand(path.toString()));
        builder.redirectErrorStream(true);
        builder.start();
    }

    // ==================== 打开 ====================

    /** 下载远端文件到临时目录并启动首选外部编辑器。 */
    public void openRemote(String remotePath) {
        openRemoteOverride(remotePath, null);
    }

    /** 下载远端文件并用用户在二级菜单中指定的编辑器打开（选择会记入首选）。 */
    public void openRemoteWith(String remotePath, ExternalEditor editor) {
        openRemoteOverride(remotePath, () -> editor);
    }

    /** 下载远端文件并用系统默认程序打开（文件面板右键「用系统默认程序打开」）。 */
    public void openRemoteWithSystemDefault(String remotePath) {
        openRemoteOverride(remotePath, ExternalEditorDetector::systemDefault);
    }

    private void openRemoteOverride(String remotePath, java.util.function.Supplier<ExternalEditor> editorOverride) {
        if (closed.get() || remotePath == null || remotePath.isBlank()) return;
        ioExecutor.submit(() -> {
            try {
                FileTransferService.RemoteFileStat stat = sshService.statForTab(tabId, remotePath);
                if (stat.directory()) {
                    interaction.notify(I18n.tr("editor.error.title"), I18n.tr("editor.open.directory", remotePath));
                    return;
                }
                if (stat.size() > EditorPreferences.maxExternalBytes()) {
                    interaction.notify(I18n.tr("editor.tooLarge.title"),
                            I18n.tr("editor.external.tooLarge", remotePath,
                                    formatSize(stat.size()), formatSize(EditorPreferences.maxExternalBytes())));
                    return;
                }
                byte[] data = sshService.readBytesForTab(tabId, remotePath, EditorPreferences.maxExternalBytes());
                EditSession session = writeTempFile(remotePath, data, stat.mtimeSeconds());
                launch(session, stat.mtimeSeconds(), editorOverride);
            } catch (Exception e) {
                log.error("External editor open failed: {}", remotePath, e);
                interaction.notify(I18n.tr("editor.external.notFound"),
                        remotePath + "\n" + e.getMessage());
            }
        });
    }

    private EditSession writeTempFile(String remotePath, byte[] data, long mtimeSeconds) throws IOException {
        String hash = sha1Hex(remotePath);
        Path dir = sessionRoot.resolve(hash);
        Files.createDirectories(dir);
        String name = fileNameOf(remotePath);
        Path tempFile = dir.resolve(name.isEmpty() ? "edit.bin" : name);
        Files.write(tempFile, data);
        EditSession session = new EditSession(remotePath, tempFile, sha256(data), data.length, mtimeSeconds);
        sessions.put(sessionKey(remotePath), session);
        registerWatch(dir, session);
        return session;
    }

    private void launch(EditSession session, long remoteMtime, java.util.function.Supplier<ExternalEditor> editorOverride) {
        ExternalEditor editor = editorOverride != null ? editorOverride.get() : resolveEditor();
        try {
            if (editor.isSystemDefault()) {
                Desktop.getDesktop().open(session.tempFile.toFile());
                interaction.statusMessage(I18n.tr("editor.external.openedDefault", session.remotePath));
                return;
            }
            List<String> command = editor.buildCommand(session.tempFile.toString());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(session.tempFile.getParent().toFile());
            builder.redirectErrorStream(true);
            session.process = builder.start();
            interaction.statusMessage(I18n.tr("editor.external.opened", editor.name(), session.remotePath));
            log.info("External editor started: {} for {}", editor.name(), session.remotePath);
        } catch (Exception e) {
            log.error("Failed to start external editor for {}", session.remotePath, e);
            interaction.notify(I18n.tr("editor.external.notFound"),
                    session.remotePath + "\n" + e.getMessage());
        }
    }

    // ==================== 监视与回传 ====================

    private void registerWatch(Path dir, EditSession session) {
        if (watchFailed || watchService == null) return;
        try {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            log.warn("Watch registration failed for {}: {}", dir, e.getMessage());
        }
    }

    private void startWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.warn("WatchService unavailable, falling back to polling: {}", e.getMessage());
            watchFailed = true;
        }
        try {
            Files.createDirectories(sessionRoot);
        } catch (IOException ignored) {
            // 注册失败时降级轮询
        }
        if (!watchFailed) {
            try {
                sessionRoot.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
            } catch (IOException e) {
                log.warn("Watch registration on session root failed, using polling: {}", e.getMessage());
                watchFailed = true;
            }
        }
        watchExecutor.submit(this::watchLoop);
    }

    /** 守护线程：WatchService 失败时降级为 2s 轮询（方案 §5.4）。 */
    private void watchLoop() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!watchFailed && watchService != null) {
                    WatchKey key = watchService.poll(2, TimeUnit.SECONDS);
                    if (key != null) {
                        for (WatchEvent<?> ignored : key.pollEvents()) {
                            scheduleChecks();
                        }
                        key.reset();
                        continue;
                    }
                }
                // 轮询兜底（含 watchFailed 的情形）：周期性直接检查
                Thread.sleep(2000);
                scheduleChecks();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("External editor watch loop error: {}", e.getMessage());
            }
        }
    }

    private void scheduleChecks() {
        for (String key : sessions.keySet()) scheduleCheck(key);
    }

    private void scheduleCheck(String sessionKey) {
        ScheduledFuture<?> existing = pendingChecks.get(sessionKey);
        if (existing != null) existing.cancel(false);
        ScheduledFuture<?> task = debounce.schedule(
                () -> checkSession(sessionKey), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingChecks.put(sessionKey, task);
    }

    private void checkSession(String sessionKey) {
        pendingChecks.remove(sessionKey);
        EditSession session = sessions.get(sessionKey);
        if (session == null || session.uploading.get() || closed.get()) return;
        try {
            if (!Files.exists(session.tempFile)) {
                // 临时文件被删除：视作编辑放弃
                sessions.remove(sessionKey);
                return;
            }
            byte[] data = readStable(session.tempFile);
            if (data == null) return;
            String hash = sha256(data);
            if (hash.equals(session.sha256)) return;
            // 内容变化：自动回传或确认
            UploadDecision decision = interaction.isAutoUpload()
                    ? UploadDecision.UPLOAD : interaction.askUpload(session.remotePath,
                    I18n.tr("editor.external.uploadPromptMessage", session.remotePath));
            switch (decision) {
                case UPLOAD -> upload(session, data, hash);
                case KEEP_LOCAL -> {
                    session.sha256 = hash;
                    session.size = data.length;
                    interaction.statusMessage(I18n.tr("editor.external.keptLocal", session.remotePath));
                }
                case DISCARD -> {
                    sessions.remove(sessionKey);
                    deleteQuietly(session.tempFile);
                    interaction.statusMessage(I18n.tr("editor.external.discarded", session.remotePath));
                }
            }
        } catch (Exception e) {
            log.warn("External editor change check failed for {}", session.remotePath, e);
        }
    }

    /** 读取临时文件，要求大小稳定（2s 内不变），避免读到原子保存的半写文件。 */
    private byte[] readStable(Path file) {
        try {
            long size = Files.size(file);
            long deadline = System.currentTimeMillis() + SIZE_STABLE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(SIZE_STABLE_INTERVAL_MS);
                long current = Files.size(file);
                if (current == size) break;
                size = current;
            }
            return Files.readAllBytes(file);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            log.warn("Could not read temp file {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void upload(EditSession session, byte[] data, String hash) {
        session.uploading.set(true);
        ioExecutor.submit(() -> {
            try {
                sshService.writeBytesForTab(tabId, session.remotePath, data, session.expectedMtime);
                EditorAudit.recordWrite(tabId, session.remotePath, data, "external-uploaded");
                session.sha256 = hash;
                session.size = data.length;
                try {
                    FileTransferService.RemoteFileStat after = sshService.statForTab(tabId, session.remotePath);
                    session.expectedMtime = after.mtimeSeconds();
                } catch (Exception ignored) {
                    // 基线更新失败只影响下次冲突检测精度
                }
                interaction.statusMessage(I18n.tr("editor.external.uploaded", session.remotePath));
            } catch (FileTransferService.RemoteFileChangedException conflict) {
                interaction.notify(I18n.tr("editor.conflict.title"),
                        I18n.tr("editor.external.conflict", session.remotePath));
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
                    interaction.notify(I18n.tr("editor.save.failed"),
                            I18n.tr("editor.save.permission", session.remotePath));
                } else {
                    interaction.notify(I18n.tr("editor.external.uploadFailed"),
                            session.remotePath + "\n" + e.getMessage());
                }
            } catch (Exception e) {
                log.error("External editor upload failed: {}", session.remotePath, e);
                interaction.notify(I18n.tr("editor.external.uploadFailed"),
                        session.remotePath + "\n" + e.getMessage());
            } finally {
                session.uploading.set(false);
            }
        });
    }

    /**
     * 解析要使用的外部编辑器：自定义命令 > 已记住的选择 > 多选时弹选择框（选择会记入偏好）>
     * 检测到的第一个 > 系统默认。
     */
    private ExternalEditor resolveEditor() {
        List<ExternalEditor> editors = ExternalEditorDetector.detectAvailable();
        if (editors.size() == 1) return editors.get(0);
        String remembered = EditorPreferences.preferredEditorName();
        for (ExternalEditor editor : editors) {
            if (editor.name().equals(remembered)) return editor;
        }
        ExternalEditor chosen = interaction.chooseEditor(editors);
        if (chosen != null) {
            EditorPreferences.setPreferredEditorName(chosen.name());
            return chosen;
        }
        return editors.get(editors.size() - 1); // 取消时退回系统默认（列表末尾）
    }

    // ==================== 生命周期 ====================

    /** 结束编辑会话：停止监视并删除本会话临时文件。 */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {
        }
        watchExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        debounce.shutdownNow();
        for (EditSession session : sessions.values()) deleteQuietly(session.tempFile);
        sessions.clear();
    }

    /** 启动清理：删除超过保留期的残留临时目录（方案 §3.3 第 7 条 / §5.4）。 */
    public static void cleanupStaleTempDirs() {
        int retentionDays = Math.max(1, EditorPreferences.tempRetentionDays());
        Path root = Path.of(System.getProperty("java.io.tmpdir"), TEMP_ROOT_NAME);
        if (!Files.isDirectory(root)) return;
        try (var stream = Files.list(root)) {
            for (Path sessionDir : stream.toList()) {
                try {
                    Instant modified = Files.getLastModifiedTime(sessionDir).toInstant();
                    if (Duration.between(modified, Instant.now()).toDays() >= retentionDays) {
                        deleteRecursively(sessionDir);
                        log.info("Cleaned stale editor temp dir: {}", sessionDir);
                    }
                } catch (IOException ignored) {
                    // 单个目录失败不影响其它清理
                }
            }
        } catch (IOException e) {
            log.debug("Editor temp cleanup skipped: {}", e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path file) {
        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }

    // ==================== 工具 ====================

    static String sessionKey(String remotePath) {
        return remotePath;
    }

    static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    static String sha256(byte[] data) {
        return EditorAudit.sha256(data);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** 一次外部编辑会话：远端路径、临时文件与回传基线。 */
    private static final class EditSession {
        final String remotePath;
        final Path tempFile;
        volatile String sha256;
        volatile long size;
        volatile long expectedMtime;
        final List<ExternalEditor> editors = new ArrayList<>();
        volatile Process process;
        final AtomicBoolean uploading = new AtomicBoolean();

        EditSession(String remotePath, Path tempFile, String sha256, long size, long expectedMtime) {
            this.remotePath = remotePath;
            this.tempFile = tempFile;
            this.sha256 = sha256;
            this.size = size;
            this.expectedMtime = expectedMtime;
        }
    }
}
