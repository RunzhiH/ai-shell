/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.util.ControllerUtils;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ssh.domain.FileInfo;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
public class FileManagerPanelController extends BaseController implements Initializable,
        com.runzhi.aishell.common.util.Cleanable {

    @FXML
    private TableView<com.runzhi.aishell.ssh.domain.FileInfo> remoteFileTable;
    
    @FXML
    private TableColumn<com.runzhi.aishell.ssh.domain.FileInfo, String> remoteNameColumn;
    
    @FXML
    private TableColumn<com.runzhi.aishell.ssh.domain.FileInfo, String> remoteSizeColumn;
    
    @FXML
    private TableColumn<com.runzhi.aishell.ssh.domain.FileInfo, String> remoteDateColumn;
    
    @FXML
    private TableColumn<com.runzhi.aishell.ssh.domain.FileInfo, String> remoteTypeColumn;
    
    @FXML
    private TableView<FileInfo> localFileTable;
    
    @FXML
    private TableColumn<FileInfo, String> localNameColumn;
    
    @FXML
    private TableColumn<FileInfo, String> localSizeColumn;
    
    @FXML
    private TableColumn<FileInfo, String> localDateColumn;
    
    @FXML
    private TableColumn<FileInfo, String> localTypeColumn;
    
    @FXML
    private TextField pathField;
    @FXML
    private TextField localPathField;

    /** 远程侧整个面板：作为拖拽上传的放置目标 */
    @FXML
    private VBox remoteDropTarget;
    
    @FXML
    private Label statusLabel;
    
    @FXML
    private Label transferSpeedLabel;
    
    @FXML
    private Label progressLabel;

    // 选中计数标签
    @FXML
    private Label selectedCountLabel;
    @FXML
    private Button cancelTransferButton;

    /** 底部状态栏的传输进度条 */
    @FXML
    private ProgressBar transferProgressBar;

    /** 当前批量传输的进度统计；由传输线程更新，渲染统一切回 FX 线程 */
    private volatile TransferProgressTracker progressTracker;

    /** 进度渲染节流：避免高频 onProgress 用 runLater 淹没 FX 线程 */
    private volatile long lastProgressRenderNano;
    private static final long PROGRESS_RENDER_INTERVAL_MS = 120;

    private ObservableList<com.runzhi.aishell.ssh.domain.FileInfo> remoteFiles = FXCollections.observableArrayList();
    private ObservableList<FileInfo> localFiles = FXCollections.observableArrayList();
    
    // SSH 服务和 Tab ID，用于获取当前连接的远程文件列表
    private com.runzhi.aishell.ssh.SSHService sshService;
    private String currentTabId;
    private Path localDirectory = Paths.get(System.getProperty("user.home"));
    private final ExecutorService transferExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sftp-transfer-queue");
        thread.setDaemon(true);
        return thread;
    });
    private volatile com.runzhi.aishell.ssh.FileTransferService.TransferControl activeTransferControl;
    private volatile Future<?> activeTransferTask;
    private final com.runzhi.aishell.ssh.TransferQueueStore transferQueueStore =
            new com.runzhi.aishell.ssh.TransferQueueStore();
    private boolean recoveryChecked;

    /** 拖拽悬停时加到远程面板上的高亮样式类 */
    private static final String DROP_TARGET_STYLE = "drop-target";

    /** 防止刷新风暴：进行中标志 + 路径代际 + 去抖 */
    private volatile boolean remoteLoading;
    private volatile String loadedRemotePath;
    private volatile String lastRequestedPath;
    private final java.util.concurrent.atomic.AtomicInteger loadGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong lastRefreshAtMs =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean panelVisible = true;

    public void setPanelVisible(boolean visible) {
        this.panelVisible = visible;
        if (visible) {
            requestRemoteRefresh(false);
        }
    }

    public boolean isPanelVisible() {
        return panelVisible;
    }

    public void applyTerminalDirectory(String directory) {
        if (directory == null || directory.isBlank()) return;
        String normalized = normalizePathKey(directory);
        if (pathField != null) {
            String current = pathField.getText();
            if (current == null || !normalized.equals(normalizePathKey(current))) {
                pathField.setText(directory);
            }
        }
        lastRequestedPath = directory;
        if (panelVisible) {
            requestRemoteRefresh(false);
        } else {
            loadedRemotePath = null;
        }
    }

    private static String normalizePathKey(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        if (p.isEmpty()) return "~";
        if (!p.startsWith("/") && !p.startsWith("~")) p = "/" + p;
        p = p.replaceAll("/+", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private void requestRemoteRefresh(boolean force) {
        String path = pathField != null ? pathField.getText() : "~";
        String key = normalizePathKey(path);
        lastRequestedPath = path;

        if (!force && key.equals(loadedRemotePath) && remoteFiles != null && !remoteFiles.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastRefreshAtMs.get();
        if (!force && now - last < 400 && key.equals(normalizePathKey(loadedRemotePath))) {
            return;
        }
        lastRefreshAtMs.set(now);
        loadRemoteFiles();
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 初始化表格列
        initTableColumns();
        
        // 设置表格数据
        remoteFileTable.setItems(remoteFiles);
        localFileTable.setItems(localFiles);
        remoteFileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        localFileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        
        // 启用多选模式
        remoteFileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        localFileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        remoteFileTable.setRowFactory(table -> {
            TableRow<com.runzhi.aishell.ssh.domain.FileInfo> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && isRemoteDirectory(row.getItem())) {
                    pathField.setText(joinRemotePath(pathField.getText(), row.getItem().getName()));
                    loadedRemotePath = null;
                    requestRemoteRefresh(true);
                }
            });
            return row;
        });
        localFileTable.setRowFactory(table -> {
            TableRow<FileInfo> row = new TableRow<>();
            // 支持把本地表格中选中的文件拖拽到远程面板上传
            row.setOnDragDetected(event -> {
                if (row.isEmpty()) {
                    event.consume();
                    return;
                }
                ObservableList<FileInfo> selected = localFileTable.getSelectionModel().getSelectedItems();
                if (selected.isEmpty()) {
                    event.consume();
                    return;
                }
                List<java.io.File> dragged = new ArrayList<>();
                for (FileInfo info : selected) {
                    dragged.add(localDirectory.resolve(info.getName()).toFile());
                }
                Dragboard board = row.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.putFiles(dragged);
                board.setContent(content);
                event.consume();
            });
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()
                        && (row.getItem().getPermission() & 0040000) != 0) {
                    Path next = localDirectory.resolve(row.getItem().getName()).normalize();
                    if (Files.isDirectory(next) && Files.isReadable(next)) {
                        localDirectory = next;
                        localPathField.setText(next.toString());
                        loadLocalFiles();
                    }
                }
            });
            return row;
        });
        
        // 监听选择变化，更新选中计数
        remoteFileTable.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<? super com.runzhi.aishell.ssh.domain.FileInfo>) c -> {
                updateSelectedCount();
            }
        );
        localFileTable.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<? super FileInfo>) c -> {
                updateSelectedCount();
            }
        );
        
        // 初始化当前路径
        if (pathField != null) {
            pathField.setText("~");
        }
        if (localPathField != null) localPathField.setText(localDirectory.toString());
        
        // 初始化状态
        updateStatus(I18n.tr("fm.ready"));
        
        // 加载本地文件列表
        loadLocalFiles();
    }

    private void initTableColumns() {
        // 使用显式值工厂，避免 Java 模块下 PropertyValueFactory 反射失败；
        // 同时把时间戳和权限数值转换为真正可显示的文本。
        remoteNameColumn.setCellValueFactory(cell -> text(cell.getValue().getName()));
        remoteSizeColumn.setCellValueFactory(cell -> text(cell.getValue().getSize()));
        remoteDateColumn.setCellValueFactory(cell -> text(formatModifiedTime(cell.getValue().getModifiedTime())));
        remoteTypeColumn.setCellValueFactory(cell -> text(formatPermission(cell.getValue().getPermission())));

        localNameColumn.setCellValueFactory(cell -> text(cell.getValue().getName()));
        localSizeColumn.setCellValueFactory(cell -> text(cell.getValue().getSize()));
        localDateColumn.setCellValueFactory(cell -> text(formatModifiedTime(cell.getValue().getModifiedTime())));
        localTypeColumn.setCellValueFactory(cell -> text(formatPermission(cell.getValue().getPermission())));
    }

    private static ReadOnlyStringWrapper text(String value) {
        return new ReadOnlyStringWrapper(value == null ? "" : value);
    }

    private static String formatModifiedTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) return "";
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochSecond(epochSeconds));
    }

    private static String formatPermission(int permission) {
        int mode = permission & 07777;
        return mode == 0 ? "" : String.format("%04o", mode);
    }

    /** 本地侧新建文件夹（在当前本地路径下创建） */
    @FXML
    private void createLocalFolder() {
        TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle(I18n.tr("fm.newfolder.title"));
            dialog.setHeaderText(I18n.tr("fm.newfolder.header"));
            dialog.setContentText(I18n.tr("fm.newfolder.content"));

        dialog.showAndWait().ifPresent(folderName -> {
            if (folderName.trim().isEmpty()) {
                        ControllerUtils.showWarningAlert(I18n.tr("fm.warn.title"), I18n.tr("fm.newfolder.name.empty"));
                return;
            }
            String parent = localPathField.getText();
            if (parent == null || parent.isBlank()) {
        showErrorAlert(I18n.tr("fm.newfolder.local.path.empty"));
                return;
            }
            java.io.File newDir = new java.io.File(parent, folderName.trim());
            if (newDir.exists()) {
        showErrorAlert(I18n.tr("fm.newfolder.exists", newDir.getAbsolutePath()));
                return;
            }
            if (newDir.mkdirs()) {
        updateStatus(I18n.tr("fm.newfolder.created", folderName));
                loadLocalFiles();
            } else {
        showErrorAlert(I18n.tr("fm.newfolder.failed", newDir.getAbsolutePath()));
        updateStatus(I18n.tr("fm.newfolder.local.failed"));
            }
        });
    }

    @FXML
    private void createNewFolder() {
        TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle(I18n.tr("fm.newfolder.title"));
            dialog.setHeaderText(I18n.tr("fm.newfolder.header"));
            dialog.setContentText(I18n.tr("fm.newfolder.content"));

        dialog.showAndWait().ifPresent(folderName -> {
            if (folderName.trim().isEmpty()) {
                        ControllerUtils.showWarningAlert(I18n.tr("fm.warn.title"), I18n.tr("fm.newfolder.name.empty"));
                return;
            }

            if (sshService == null || currentTabId == null) {
        showErrorAlert(I18n.tr("fm.newfolder.ssh.disconnected"));
                return;
            }
            com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
                try {
                    String remotePath = joinRemotePath(pathField.getText(), folderName.trim());
                    sshService.createDirectoryForTab(currentTabId, remotePath);
                    
                    Platform.runLater(() -> {
                updateStatus(I18n.tr("fm.newfolder.created", folderName));
        showInfoAlert(I18n.tr("fm.newfolder.success.title"), I18n.tr("fm.newfolder.success.content", folderName));
                        loadRemoteFiles();
                    });
                } catch (Exception e) {
                    log.error("Create folder error", e);
                    Platform.runLater(() -> {
        showErrorAlert(I18n.tr("fm.newfolder.failed", e.getMessage()));
        updateStatus(I18n.tr("fm.create.failed.status"));
                    });
                }
            });
        });
    }

    @FXML
    private void deleteSelectedItems() {
        // 删除选中的本地文件
        ObservableList<FileInfo> selectedLocalFiles = localFileTable.getSelectionModel().getSelectedItems();
        ObservableList<com.runzhi.aishell.ssh.domain.FileInfo> selectedRemoteFiles = remoteFileTable.getSelectionModel().getSelectedItems();
        
        if (selectedLocalFiles.isEmpty() && selectedRemoteFiles.isEmpty()) {
        ControllerUtils.showWarningAlert(I18n.tr("fm.warn.title"), I18n.tr("fm.delete.no.selection"));
            return;
        }

        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle(I18n.tr("fm.delete.confirm.title"));
        confirmAlert.setHeaderText(I18n.tr("fm.delete.confirm.header"));
        confirmAlert.setContentText(I18n.tr("fm.delete.confirm.content"));

        confirmAlert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                // 在后台线程执行删除
                com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
                    try {
                        for (FileInfo file : new ArrayList<>(selectedLocalFiles)) {
                            java.nio.file.Files.delete(localDirectory.resolve(file.getName()));
                        }
                        for (com.runzhi.aishell.ssh.domain.FileInfo file : new ArrayList<>(selectedRemoteFiles)) {
                            String remotePath = joinRemotePath(pathField.getText(), file.getName());
                            boolean directory = (file.getPermission() & 0040000) != 0;
                            sshService.deletePathForTab(currentTabId, remotePath, directory);
                        }
                        
                        Platform.runLater(() -> {
        updateStatus(I18n.tr("fm.delete.done"));
        showInfoAlert(I18n.tr("fm.delete.done.title"), I18n.tr("fm.delete.done.content"));
                            loadLocalFiles(); // 重新加载本地文件列表
                            loadRemoteFiles(); // 重新加载远程文件列表
                        });
                    } catch (Exception e) {
                        log.error("Delete error", e);
                        Platform.runLater(() -> {
        showErrorAlert(I18n.tr("fm.delete.failed", e.getMessage()));
        updateStatus(I18n.tr("fm.delete.failed.status"));
                        });
                    }
                });
            }
        });
    }

    @FXML
    public void refreshFileList() {
        loadLocalFiles();
        requestRemoteRefresh(true);
        updateStatus(I18n.tr("fm.refresh.all"));
    }

    private void loadLocalFiles() {
        Path requestedDirectory = localDirectory;
        // 在后台线程加载本地文件
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            try {
                List<FileInfo> loaded = new ArrayList<>();
                try (Stream<Path> paths = Files.list(requestedDirectory)) {
                    paths.sorted((left, right) -> left.getFileName().toString()
                                    .compareToIgnoreCase(right.getFileName().toString()))
                            .forEach(path -> {
                                try {
                                    // Windows 用户目录包含 My Documents 等兼容性目录联接；
                                    // 目标不存在时 Files.list 仍可能枚举到它，但读取属性会失败。
                                    if (!Files.exists(path) || !Files.isReadable(path)) return;
                                    BasicFileAttributes attributes = Files.readAttributes(
                                            path, BasicFileAttributes.class);
                                    String size = attributes.isDirectory() ? "<DIR>"
                                            : formatLocalSize(attributes.size());
                                    int permission = attributes.isDirectory() ? 0040000 : 0;
                                    loaded.add(new FileInfo(path.getFileName().toString(), size, permission,
                                            attributes.lastModifiedTime().toMillis() / 1000));
                                } catch (NoSuchFileException ignored) {
                                    // 失效链接或枚举后被删除的文件属于正常竞争条件，直接跳过。
                                } catch (Exception e) {
                                    log.debug("跳过无法读取的本地文件: {} ({})", path, e.getMessage());
                                }
                            });
                }
                Platform.runLater(() -> {
                    // 用户快速切换目录时，不让较早的后台结果覆盖当前目录。
                    if (requestedDirectory.equals(localDirectory)) localFiles.setAll(loaded);
                });
            } catch (Exception e) {
                log.error("Load local files error", e);
                Platform.runLater(() -> {
        showErrorAlert(I18n.tr("fm.load.local.failed", e.getMessage()));
                });
            }
        });
    }

    private void loadRemoteFiles() {
        if (sshService == null || currentTabId == null || currentTabId.isBlank()) {
            return;
        }
        if (!isCurrentTabConnected()) {
            Platform.runLater(() -> {
                remoteFiles.clear();
            updateStatus(I18n.tr("fm.ssh.disconnected"));
            });
            return;
        }
        if (!panelVisible) {
            return;
        }

        final String requestPath = pathField != null ? pathField.getText() : "~";
        final String requestKey = normalizePathKey(requestPath);
        final int generation = loadGeneration.incrementAndGet();
        remoteLoading = true;

        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            try {
                if (generation != loadGeneration.get()) {
                    return;
                }
            updateStatus(I18n.tr("fm.remote.loading"));
                ObservableList<com.runzhi.aishell.ssh.domain.FileInfo> files =
                        sshService.listFilesForTab(currentTabId, requestPath);

                if (generation != loadGeneration.get()) {
                    return;
                }
                Platform.runLater(() -> {
                    if (generation != loadGeneration.get()) return;
                    boolean samePath = requestKey.equals(normalizePathKey(loadedRemotePath));
                    if (samePath && files != null && remoteFiles != null && !remoteFiles.isEmpty()
                            && remoteFiles.size() == files.size()) {
                        remoteLoading = false;
                        updateStatus(I18n.tr("fm.ready"));
                        return;
                    }
                    if (remoteFiles != null) {
                        remoteFiles.clear();
                        if (files != null) remoteFiles.addAll(files);
                    }
                    loadedRemotePath = requestKey;
                    remoteLoading = false;
        updateStatus(files == null || files.isEmpty() ? I18n.tr("fm.remote.empty") : I18n.tr("fm.ready"));
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.contains("NO_SUCH_FILE") || message.contains("no such file")) {
                    Platform.runLater(() -> {
                        if (generation == loadGeneration.get()) {
                            remoteFiles.clear();
                            loadedRemotePath = requestKey;
                            remoteLoading = false;
        updateStatus(I18n.tr("fm.remote.notexist", requestPath));
                        }
                    });
                } else {
                    log.error("Load remote files error", e);
                    Platform.runLater(() -> {
                        if (generation == loadGeneration.get()) {
                            remoteLoading = false;
        showErrorAlert(I18n.tr("fm.remote.load.failed", e.getMessage()));
        updateStatus(I18n.tr("fm.remote.load.failed.status"));
                        }
                    });
                }
            }
        });
    }

    private void updateStatus(String status) {
        Runnable update = () -> {
            if (statusLabel != null) statusLabel.setText(status);
        };
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    /**
     * 本地路径输入框回车：切换到用户输入的本地目录。
     * 支持 ~ 展开为用户主目录，相对路径基于当前目录解析；
     * 输入非法时回退到原路径并提示。
     */
    @FXML
    private void navigateLocalPath() {
        String text = localPathField.getText();
        if (text == null || text.isBlank()) {
            localPathField.setText(localDirectory.toString());
            return;
        }
        Path candidate = resolveLocalPathInput(text);
        if (!Files.isDirectory(candidate)) {
        showErrorAlert(I18n.tr("fm.local.path.invalid", candidate));
            localPathField.setText(localDirectory.toString());
            return;
        }
        if (!Files.isReadable(candidate)) {
        showErrorAlert(I18n.tr("fm.local.path.unreadable", candidate));
            localPathField.setText(localDirectory.toString());
            return;
        }
        changeLocalDirectory(candidate);
    }

    /** 弹出系统目录选择器，选定后切换本地目录 */
    @FXML
    private void browseLocalDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.tr("fm.chooser.local.title"));
        java.io.File initial = localDirectory.toFile();
        chooser.setInitialDirectory(initial.isDirectory()
                ? initial : new java.io.File(System.getProperty("user.home")));
        java.io.File selected;
        try {
            selected = chooser.showDialog(localPathField.getScene().getWindow());
        } catch (Exception e) {
            log.warn("打开目录选择器失败", e);
            return;
        }
        if (selected != null && selected.isDirectory()) {
            changeLocalDirectory(selected.toPath().toAbsolutePath().normalize());
        }
    }

    /** 切换当前本地目录并刷新本地文件列表 */
    private void changeLocalDirectory(Path directory) {
        if (directory == null || directory.equals(localDirectory)) return;
        localDirectory = directory;
        if (localPathField != null) localPathField.setText(directory.toString());
        loadLocalFiles();
        updateStatus(I18n.tr("fm.local.switched", directory));
    }

    private Path resolveLocalPathInput(String text) {
        String trimmed = text.trim().replace('\\', '/');
        if (trimmed.startsWith("~")) {
            trimmed = System.getProperty("user.home") + trimmed.substring(1);
        }
        Path candidate = Paths.get(trimmed);
        if (!candidate.isAbsolute()) candidate = localDirectory.resolve(candidate);
        return candidate.normalize();
    }

    /**
     * 本地工具栏“刷新”按钮：以路径栏显示的目录为准重新加载本地文件列表。
     * 用户改过路径但未回车时，刷新会直接切到路径栏所指目录，避免“列表不动”的观感。
     */
    @FXML
    private void refreshLocalList() {
        if (localPathField != null) {
            String text = localPathField.getText();
            if (text != null && !text.isBlank()) {
                try {
                    Path candidate = resolveLocalPathInput(text);
                    if (Files.isDirectory(candidate) && Files.isReadable(candidate)
                            && !candidate.equals(localDirectory)) {
                        log.info("本地刷新：路径栏与当前目录不一致，切换到 {}", candidate);
                        changeLocalDirectory(candidate);
                        return;
                    }
                } catch (Exception invalidInput) {
                    // 非法输入时不报错，按当前目录刷新
                    log.debug("本地刷新：路径栏输入无效，按当前目录刷新（{}）", invalidInput.getMessage());
                }
            }
        }
        loadLocalFiles();
        updateStatus(I18n.tr("fm.local.refreshed", localDirectory));
    }

    @FXML
    private void navigateRemotePath() {
        String path = pathField.getText();
        pathField.setText(path == null || path.isBlank()
                ? "/" : path.trim().replace('\\', '/'));
        loadedRemotePath = null;
        requestRemoteRefresh(true);
    }

    private boolean isCurrentTabConnected() {
        if (sshService == null || currentTabId == null || currentTabId.isBlank()) return false;
        com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.getTabConnection(currentTabId);
        return connection != null && connection.isConnected();
    }

    private void updateProgress(String task, int percentage) {
        Runnable update = () -> {
            if (progressLabel != null) progressLabel.setText(I18n.tr("fm.progress.label", percentage));
            if (statusLabel != null) statusLabel.setText(task);
        };
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    private void showInfoAlert(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    @Override
    public void showErrorAlert(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.tr("fm.error.title"));
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }


    /**
     * 设置 SSH 服务和当前 Tab ID
     */
    public void setSshService(com.runzhi.aishell.ssh.SSHService sshService, String tabId) {
        this.sshService = sshService;
        this.currentTabId = tabId;
        log.info("FileManagerPanelController 已设置 SSH 服务，Tab ID: {}", tabId);
        if (sshService != null && tabId != null) {
            String directory = sshService.getCurrentWorkingDirectoryForTab(tabId);
            if (directory != null && pathField != null) {
                String normalized = normalizePathKey(directory);
                String current = pathField.getText();
                if (current == null || !normalized.equals(normalizePathKey(current))) {
                    pathField.setText(directory);
                    loadedRemotePath = null;
                }
            }
            if (panelVisible) {
                requestRemoteRefresh(false);
            }
            if (!recoveryChecked) {
                recoveryChecked = true;
                Platform.runLater(this::offerTransferRecovery);
            }
        }
    }

    /**
     * 更新选中文件计数显示
     */
    private void updateSelectedCount() {
        int remoteSelected = remoteFileTable.getSelectionModel().getSelectedItems().size();
        int localSelected = localFileTable.getSelectionModel().getSelectedItems().size();
        int total = remoteSelected + localSelected;
        
        if (selectedCountLabel != null) {
            if (total > 0) {
                selectedCountLabel.setText(I18n.tr("fm.selected.count", total));
                selectedCountLabel.setVisible(true);
            } else {
                selectedCountLabel.setText("");
                selectedCountLabel.setVisible(false);
            }
        }
    }

    /**
     * 批量下载优化版本
     */
    @FXML
    private void downloadFiles() {
        // 从远程服务器下载文件
        updateStatus(I18n.tr("fm.download.start"));
        
        // 获取选中的远程文件
        ObservableList<com.runzhi.aishell.ssh.domain.FileInfo> selectedRemoteFiles = remoteFileTable.getSelectionModel().getSelectedItems();
        
        if (selectedRemoteFiles.isEmpty()) {
            ControllerUtils.showWarningAlert(I18n.tr("fm.warn.title"), I18n.tr("fm.download.no.selection"));
            updateStatus(I18n.tr("fm.no.selection"));
            return;
        }

        int selectedCount = selectedRemoteFiles.size();
        log.info("准备批量下载 {} 个文件", selectedCount);
        
        // 检查是否有 SSH 服务
        if (!isCurrentTabConnected()) {
            showErrorAlert(I18n.tr("fm.download.disconnected"));
            updateStatus(I18n.tr("fm.ssh.disconnected"));
            return;
        }

        // 获取本地下载目录
        String localDirPath = localDirectory.toString();
        
        // 构建远程文件路径列表
        List<String> remoteFilePaths = new ArrayList<>();
        for (com.runzhi.aishell.ssh.domain.FileInfo fileInfo : selectedRemoteFiles) {
            remoteFilePaths.add(joinRemotePath(pathField.getText(), fileInfo.getName()));
        }

        boolean hasCollision = selectedRemoteFiles.stream()
                .anyMatch(file -> Files.exists(localDirectory.resolve(file.getName())));
        com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy policy = hasCollision
                ? chooseOverwritePolicy(I18n.tr("fm.collision.local"))
                : com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy.OVERWRITE;
        if (policy == null) {
            updateStatus(I18n.tr("fm.download.cancelled"));
            return;
        }

        beginTransfer(selectedCount, -1);
        var queuedJob = transferQueueStore.enqueue(com.runzhi.aishell.ssh.TransferQueueStore.Direction.DOWNLOAD,
                sshService.getHostForTab(currentTabId), sshService.getUsernameForTab(currentTabId),
                remoteFilePaths, localDirPath, policy.name());
        activeTransferTask = transferExecutor.submit(() -> {
            try {
                transferQueueStore.markRunning(queuedJob.id());
                updateProgress(I18n.tr("fm.download.starting", selectedCount), 0);
                
                // 调用 SSHService 的批量下载方法
                sshService.downloadFilesForTab(currentTabId, remoteFilePaths, localDirPath,
                        createProgressCallback(I18n.tr("fm.action.download")), policy, activeTransferControl);
                transferQueueStore.complete(queuedJob.id());
                
                Platform.runLater(() -> {
                    updateStatus(I18n.tr("fm.download.done"));
                    showInfoAlert(I18n.tr("fm.download.done.title"), I18n.tr("fm.download.done.content", selectedCount, localDirPath));
                    loadLocalFiles(); // 重新加载本地文件列表
                    finishTransfer();
                });
            } catch (Exception e) {
                transferQueueStore.markInterrupted(queuedJob.id());
                log.error("批量下载失败", e);
                Platform.runLater(() -> {
                    showErrorAlert(I18n.tr("fm.download.failed", e.getMessage()));
                    updateStatus(I18n.tr("fm.download.failed.status"));
                    finishTransfer();
                });
            }
        });
    }

    /**
     * 批量上传优化版本
     */
    @FXML
    private void uploadFiles() {
        // 上传文件到远程服务器
        updateStatus(I18n.tr("fm.upload.start"));
        
        // 获取选中的本地文件
        ObservableList<FileInfo> selectedLocalFiles = localFileTable.getSelectionModel().getSelectedItems();
        
        if (selectedLocalFiles.isEmpty()) {
            ControllerUtils.showWarningAlert(I18n.tr("fm.warn.title"), I18n.tr("fm.upload.no.selection"));
            updateStatus(I18n.tr("fm.no.selection"));
            return;
        }

        if (!isCurrentTabConnected()) {
            showErrorAlert(I18n.tr("fm.upload.disconnected"));
            updateStatus(I18n.tr("fm.ssh.disconnected"));
            return;
        }

        // 构建本地文件路径列表
        List<String> localFilePaths = new ArrayList<>();
        for (FileInfo fileInfo : selectedLocalFiles) {
            localFilePaths.add(localDirectory.resolve(fileInfo.getName()).toString());
        }
        submitUpload(localFilePaths, "批量上传");
    }

    /**
     * 上传公共入口：工具栏按钮上传与拖拽上传共用同一套队列、覆盖策略与进度回调。
     */
    private void submitUpload(List<String> localFilePaths, String action) {
        int selectedCount = localFilePaths.size();
        log.info("{}：准备上传 {} 个文件", action, selectedCount);

        // 本地文件大小可直接读取：总字节数已知，进度按字节加权；读不到则按文件数加权。
        // 目录按整棵树统计，文件数为叶子文件数，与上传服务器的递归口径一致。
        long totalBytes = 0;
        long fileLeafCount = 0;
        boolean totalKnown = true;
        for (String path : localFilePaths) {
            try {
                java.io.File candidate = new java.io.File(path);
                if (candidate.isDirectory()) {
                    long[] tree = directoryLayout(candidate);
                    totalBytes += tree[0];
                    fileLeafCount += tree[1];
                } else {
                    totalBytes += candidate.length();
                    fileLeafCount++;
                }
            } catch (Exception sizeLookupFailed) {
                totalKnown = false;
            }
        }

        // 获取远程目录路径
        String remoteDirPath = pathField.getText();

        com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy policy =
                chooseOverwritePolicy(I18n.tr("fm.collision.remote"));
        if (policy == null) {
            updateStatus(I18n.tr("fm.upload.cancelled"));
            return;
        }

        beginTransfer((int) Math.max(1, fileLeafCount), totalKnown ? totalBytes : -1);
        var queuedJob = transferQueueStore.enqueue(com.runzhi.aishell.ssh.TransferQueueStore.Direction.UPLOAD,
                sshService.getHostForTab(currentTabId), sshService.getUsernameForTab(currentTabId),
                localFilePaths, remoteDirPath, policy.name());
        activeTransferTask = transferExecutor.submit(() -> {
            try {
                transferQueueStore.markRunning(queuedJob.id());
                updateProgress(I18n.tr("fm.upload.starting", selectedCount), 0);
                
                // 调用 SSHService 的批量上传方法
                sshService.uploadFilesForTab(currentTabId, localFilePaths, remoteDirPath,
                        createProgressCallback(I18n.tr("fm.action.upload")), policy, activeTransferControl);
                transferQueueStore.complete(queuedJob.id());
                
                Platform.runLater(() -> {
                    updateStatus(I18n.tr("fm.upload.done"));
                    showInfoAlert(I18n.tr("fm.upload.done.title"), I18n.tr("fm.upload.done.content", selectedCount, remoteDirPath));
                    loadRemoteFiles(); // 重新加载远程文件列表
                    finishTransfer();
                });
            } catch (Exception e) {
                transferQueueStore.markInterrupted(queuedJob.id());
                log.error("批量上传失败", e);
                Platform.runLater(() -> {
                    showErrorAlert(I18n.tr("fm.upload.failed", e.getMessage()));
                    updateStatus(I18n.tr("fm.upload.failed.status"));
                    finishTransfer();
                });
            }
        });
    }

    // ==================== 拖拽上传（远程侧作为放置目标） ====================

    /** 拖拽悬停在远程面板上：携带文件时接受 COPY 传输模式 */
    @FXML
    private void onRemoteDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    /** 拖拽进入远程面板：加高亮样式并提示目标路径 */
    @FXML
    private void onRemoteDragEntered(DragEvent event) {
        if (event.getDragboard().hasFiles() && remoteDropTarget != null) {
            remoteDropTarget.getStyleClass().add(DROP_TARGET_STYLE);
        updateStatus(I18n.tr("fm.drop.hint", pathField.getText()));
        }
        event.consume();
    }

    /** 拖拽离开远程面板：移除高亮样式 */
    @FXML
    private void onRemoteDragExited(DragEvent event) {
        if (remoteDropTarget != null) {
            remoteDropTarget.getStyleClass().remove(DROP_TARGET_STYLE);
        }
        event.consume();
    }

    /** 放下：把拖入的文件（资源管理器拖入或本地表格拖出）上传到当前远程目录 */
    @FXML
    private void onRemoteDragDropped(DragEvent event) {
        Dragboard board = event.getDragboard();
        List<java.io.File> dropped = board.hasFiles()
                ? new ArrayList<>(board.getFiles()) : new ArrayList<>();
        event.setDropCompleted(!dropped.isEmpty());
        event.consume();
        if (remoteDropTarget != null) {
            remoteDropTarget.getStyleClass().remove(DROP_TARGET_STYLE);
        }
        if (dropped.isEmpty()) return;
        if (!isCurrentTabConnected()) {
        showErrorAlert(I18n.tr("fm.upload.disconnected"));
            updateStatus(I18n.tr("fm.ssh.disconnected"));
            return;
        }
        List<String> localFilePaths = new ArrayList<>();
        for (java.io.File file : dropped) {
            localFilePaths.add(file.getAbsolutePath());
        }
        submitUpload(localFilePaths, "拖拽上传");
    }

    @FXML
    private void navigateLocalToParent() {
        Path parent = localDirectory.getParent();
        if (parent == null || !Files.isDirectory(parent) || !Files.isReadable(parent)) return;
        localDirectory = parent;
        localPathField.setText(parent.toString());
        loadLocalFiles();
    }

    private static String joinRemotePath(String directory, String name) {
        String base = directory == null || directory.isBlank() ? "/" : directory;
        return (base.endsWith("/") ? base : base + "/") + name;
    }

    private void offerTransferRecovery() {
        List<com.runzhi.aishell.ssh.TransferQueueStore.Job> jobs =
                transferQueueStore.recoverable(sshService.getHostForTab(currentTabId),
                        sshService.getUsernameForTab(currentTabId));
        if (jobs.isEmpty()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.tr("fm.recover.content", jobs.size()),
                ButtonType.YES, ButtonType.NO);
        alert.setTitle(I18n.tr("fm.recover.title"));
        alert.setHeaderText(I18n.tr("fm.recover.header", sshService.getUsernameForTab(currentTabId),
                sshService.getHostForTab(currentTabId)));
        if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        for (var job : jobs) submitRecoveredJob(job);
    }

    private void submitRecoveredJob(com.runzhi.aishell.ssh.TransferQueueStore.Job job) {
        activeTransferTask = transferExecutor.submit(() -> {
            com.runzhi.aishell.ssh.FileTransferService.TransferControl control =
                    new com.runzhi.aishell.ssh.FileTransferService.TransferControl();
            try {
                transferQueueStore.markRunning(job.id());
                if (job.direction() == com.runzhi.aishell.ssh.TransferQueueStore.Direction.UPLOAD) {
                    sshService.uploadFilesForTab(currentTabId, job.sources(), job.destination(),
                            createProgressCallback(I18n.tr("fm.action.upload.resume")),
                            com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy.RESUME, control);
                } else {
                    sshService.downloadFilesForTab(currentTabId, job.sources(), job.destination(),
                            createProgressCallback(I18n.tr("fm.action.download.resume")),
                            com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy.RESUME, control);
                }
                transferQueueStore.complete(job.id());
                updateStatus(I18n.tr("fm.recover.done"));
            } catch (Exception e) {
                transferQueueStore.markInterrupted(job.id());
                updateStatus(I18n.tr("fm.recover.failed", e.getMessage()));
            }
        });
    }

    private boolean isRemoteDirectory(com.runzhi.aishell.ssh.domain.FileInfo file) {
        return file != null && (file.getPermission() & 0040000) != 0;
    }

    @FXML
    private void navigateToParent() {
        String path = pathField.getText();
        if (path == null || path.isBlank() || "/".equals(path) || "~".equals(path)) return;
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int separator = normalized.lastIndexOf('/');
        pathField.setText(separator <= 0 ? "/" : normalized.substring(0, separator));
        loadedRemotePath = null;
        requestRemoteRefresh(true);
    }

    private com.runzhi.aishell.ssh.FileTransferService.ProgressCallback createProgressCallback(String action) {
        return new com.runzhi.aishell.ssh.FileTransferService.ProgressCallback() {
            @Override
            public void onProgress(String fileName, long current, long total) {
                TransferProgressTracker tracker = progressTracker;
                if (tracker != null) tracker.onProgress(fileName, current, total);
                renderTransferProgress(action, false);
            }

            @Override
            public void onError(String fileName, String errorMessage) {
                updateStatus(I18n.tr("fm.transfer.failed", action, fileName, errorMessage));
            }

            @Override
            public void onComplete(String fileName) {
                TransferProgressTracker tracker = progressTracker;
                if (tracker != null) tracker.onComplete(fileName);
                renderTransferProgress(action, true);
            }
        };
    }

    /** 把传输进度渲染到底栏：进度条 + 状态文本 + 字节数 + 平均速度 */
    private void renderTransferProgress(String action, boolean force) {
        TransferProgressTracker tracker = progressTracker;
        if (tracker == null) return;
        long now = System.nanoTime();
        if (!force && now - lastProgressRenderNano < PROGRESS_RENDER_INTERVAL_MS * 1_000_000L) return;
        lastProgressRenderNano = now;

        final int doneFiles = tracker.completedFiles;
        final int totalFiles = tracker.fileCount;
        final double percent = tracker.percent();
        final long transferred = tracker.cumulativeBytes();
        final long totalBytes = tracker.totalBytes;
        final double speed = tracker.averageSpeedBytesPerSecond();
        final String fileName = tracker.activeFile;

        Platform.runLater(() -> {
            if (transferProgressBar != null) transferProgressBar.setProgress(percent);
            updateStatus(I18n.tr("fm.transfer.progress", action, doneFiles, totalFiles, fileName));
            if (progressLabel != null) {
                String text = Math.round(percent * 100) + "%";
                if (totalBytes > 0) text += " · " + formatLocalSize(transferred) + "/" + formatLocalSize(totalBytes);
                progressLabel.setText(text);
            }
            if (transferSpeedLabel != null) {
                String speedText = formatSpeed(speed);
                transferSpeedLabel.setText(speedText.isEmpty() ? I18n.tr("fm.transferring") : speedText);
            }
        });
    }

    private com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy chooseOverwritePolicy(String header) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.tr("fm.collision.title"));
        alert.setHeaderText(header);
        alert.setContentText(I18n.tr("fm.collision.content"));
        ButtonType overwrite = new ButtonType(I18n.tr("fm.collision.overwrite"), ButtonBar.ButtonData.YES);
        ButtonType resume = new ButtonType(I18n.tr("fm.collision.resume"), ButtonBar.ButtonData.APPLY);
        ButtonType skip = new ButtonType(I18n.tr("fm.collision.skip"), ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(resume, overwrite, skip, ButtonType.CANCEL);
        return alert.showAndWait().map(result -> {
            if (result == resume) return com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy.RESUME;
            if (result == overwrite) return com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy.OVERWRITE;
            if (result == skip) return com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy.SKIP;
            return null;
        }).orElse(null);
    }

    private void beginTransfer(int fileCount, long totalBytes) {
        activeTransferControl = new com.runzhi.aishell.ssh.FileTransferService.TransferControl();
        progressTracker = new TransferProgressTracker(fileCount, totalBytes);
        lastProgressRenderNano = 0L;
        if (cancelTransferButton != null) {
            cancelTransferButton.setVisible(true);
            cancelTransferButton.setManaged(true);
        }
        if (transferProgressBar != null) {
            transferProgressBar.setProgress(0);
            transferProgressBar.setVisible(true);
            transferProgressBar.setManaged(true);
        }
    }

    private void finishTransfer() {
        activeTransferControl = null;
        activeTransferTask = null;
        progressTracker = null;
        if (cancelTransferButton != null) {
            cancelTransferButton.setVisible(false);
            cancelTransferButton.setManaged(false);
        }
        if (transferProgressBar != null) {
            transferProgressBar.setVisible(false);
            transferProgressBar.setManaged(false);
        }
        if (transferSpeedLabel != null) transferSpeedLabel.setText("");
    }

    @FXML
    private void cancelTransfer() {
        com.runzhi.aishell.ssh.FileTransferService.TransferControl control = activeTransferControl;
        if (control != null) control.cancel();
        Future<?> task = activeTransferTask;
        if (task != null) task.cancel(true);
        finishTransfer();
        updateStatus(I18n.tr("fm.transfer.cancelled"));
    }

    /**
     * 单次批量传输的进度统计。
     * 文件级回调由单线程传输执行器按序触发，无需额外同步；
     * percent() 与 averageSpeedBytesPerSecond() 只读快照，渲染时切回 FX 线程。
     */
    private static final class TransferProgressTracker {
        private final int fileCount;
        /** 总字节数；-1 表示未知（下载时远程大小无法提前获取），此时按文件数加权 */
        private final long totalBytes;
        private final long startNano = System.nanoTime();

        private int completedFiles;
        private long completedBytes;
        private String activeFile = "";
        private long activeCurrent;
        private long activeTotal;

        TransferProgressTracker(int fileCount, long totalBytes) {
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }

        void onProgress(String fileName, long current, long total) {
            if (fileName != null && !fileName.equals(activeFile)) {
                // 切到下一个文件：提交上一个文件的已传字节
                completedBytes += Math.max(0, activeCurrent);
                activeCurrent = 0;
                activeFile = fileName;
            }
            activeCurrent = current;
            activeTotal = total;
        }

        void onComplete(String fileName) {
            if (fileName != null && !fileName.equals(activeFile)) {
                // 对应文件没有经过 onProgress（如断点续传秒传完成）
                completedBytes += Math.max(0, activeCurrent);
                activeCurrent = 0;
                activeFile = fileName;
            }
            completedBytes += Math.max(0, activeCurrent);
            activeCurrent = 0;
            activeTotal = 0;
            completedFiles++;
        }

        long cumulativeBytes() {
            return completedBytes + Math.max(0, activeCurrent);
        }

        double percent() {
            if (totalBytes > 0) return Math.min(1.0, (double) cumulativeBytes() / (double) totalBytes);
            if (fileCount <= 0) return 0;
            double done = completedFiles;
            if (activeTotal > 0) done += (double) activeCurrent / (double) activeTotal;
            return Math.min(1.0, done / (double) fileCount);
        }

        double averageSpeedBytesPerSecond() {
            long elapsedSeconds = (System.nanoTime() - startNano) / 1_000_000_000L;
            return elapsedSeconds <= 0 ? 0 : (double) cumulativeBytes() / (double) elapsedSeconds;
        }
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond <= 0) return "";
        if (bytesPerSecond < 1024) return String.format("%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        if (bytesPerSecond < 1024L * 1024 * 1024) return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
        return String.format("%.1f GB/s", bytesPerSecond / (1024.0 * 1024 * 1024));
    }

    /** 目录树统计：返回 [0]=全部叶子文件字节数，[1]=叶子文件数 */
    private static long[] directoryLayout(java.io.File dir) {
        long bytes = 0;
        long count = 0;
        java.io.File[] children = dir.listFiles();
        if (children == null) return new long[] {0, 0};
        for (java.io.File child : children) {
            if (child.isDirectory()) {
                long[] sub = directoryLayout(child);
                bytes += sub[0];
                count += sub[1];
            } else {
                bytes += child.length();
                count++;
            }
        }
        return new long[] {bytes, count};
    }

    private static String formatLocalSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 窗口关闭时由 NavigationManager 调用，取消进行中的传输并关闭传输线程池。
     */
    @Override
    public void onWindowClosing() {
        dispose();
    }

    public void dispose() {
        com.runzhi.aishell.ssh.FileTransferService.TransferControl control = activeTransferControl;
        if (control != null) control.cancel();
        Future<?> task = activeTransferTask;
        if (task != null) task.cancel(true);
        transferExecutor.shutdownNow();
        activeTransferControl = null;
        activeTransferTask = null;
    }
}
