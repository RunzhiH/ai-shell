/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.util.ControllerUtils;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
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
    private volatile String currentTabId;
    /** 已销毁的面板不再接收会话通知或异步文件列表。 */
    private volatile boolean disposed;
    /** 会话断开后立即清理旧列表，连接恢复时刷新同一绑定目标。 */
    private final Runnable sessionStateListener = () -> Platform.runLater(() -> {
        if (disposed) return;
        refreshTargetDisplay();
        if (this.panelVisible || !isCurrentTabConnected()) loadRemoteFiles();
    });
    /** 会话关闭后仍保留原目标名称，避免分离窗口失去归属。 */
    private ConnectionParams boundTargetParams;
    /** Current remote endpoint, always visible above its file table. */
    @FXML private Label remoteSessionLabel;
    /** Secondary action shown only in the embedded drawer; the standalone window is already detached. */
    @FXML private Button openStandaloneButton;
    /** Standalone windows keep the session captured at open time; embedded drawers keep their own tab. */
    private boolean standalone;
    private final javafx.beans.property.ReadOnlyStringWrapper targetDisplay =
            new javafx.beans.property.ReadOnlyStringWrapper(I18n.tr("fm.target.none"));
    private final javafx.beans.property.ReadOnlyStringWrapper targetTooltip =
            new javafx.beans.property.ReadOnlyStringWrapper(I18n.tr("fm.target.none"));
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

    @FXML private VBox fileManagerArea;
    @FXML private TabPane fileModes;
    @FXML private Tab localBrowserTab,remoteBrowserTab,transferQueueTab;
    @FXML private javafx.scene.layout.StackPane localTreeHost,remoteTreeHost,queueHost;
    @FXML private Label localDirectoryLabel,remoteDirectoryLabel;
    @FXML private javafx.scene.control.SplitPane localFileSplit,remoteFileSplit;
    private com.runzhi.aishell.ui.DirectoryTree localTree,remoteTree;
    private com.runzhi.aishell.ui.TransferQueueView queueView;
    private final java.util.Map<String,String> remotePaths=new java.util.HashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger localGeneration=new java.util.concurrent.atomic.AtomicInteger();
    private Path requestedLocalDirectory;
    private volatile String currentTransferJobId;
    private final java.util.Set<String> ownedQueueJobs=java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Successful paths are the sole source for file actions, even while a failed path remains in the editor. */
    private String remoteDirectory() { return loadedRemotePath==null ? (pathField==null?"~":pathField.getText()) : loadedRemotePath; }
    private void updateDirectoryLabels() {
        String endpoint=sshService==null || currentTabId==null ? "—" : sshService.getUsernameForTab(currentTabId)+"@"+sshService.getHostForTab(currentTabId)+":"+endpointPort(sshService,currentTabId);
        if(localDirectoryLabel!=null) {
            String text=I18n.tr("fm.compact.uploadTarget",endpoint,remoteDirectory());
            localDirectoryLabel.setText(text); localDirectoryLabel.setTooltip(new Tooltip(text));
        }
        if(remoteDirectoryLabel!=null) {
            String text=I18n.tr("fm.compact.downloadTarget",localDirectory);
            remoteDirectoryLabel.setText(text); remoteDirectoryLabel.setTooltip(new Tooltip(text));
        }
    }
    private boolean retryEndpoint(com.runzhi.aishell.ssh.TransferQueueStore.Job job) {
        return job!=null && activeTransferControl==null && sshService!=null && currentTabId!=null && isCurrentTabConnected()
                && job.port()==endpointPort(sshService,currentTabId) && job.host().equals(sshService.getHostForTab(currentTabId))
                && job.username().equals(sshService.getUsernameForTab(currentTabId));
    }
    private void initializeNavigation() {
        if(localTreeHost==null) return;
        compactDirectorySplit(localFileSplit,localTreeHost);
        compactDirectorySplit(remoteFileSplit,remoteTreeHost);
        var io=com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool();
        localTree=new com.runzhi.aishell.ui.DirectoryTree(io,()->path->{
            try(var children=Files.list(Paths.get(path))) {
                return children.filter(child->Files.isDirectory(child,java.nio.file.LinkOption.NOFOLLOW_LINKS)).map(Path::toString).toList();
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        },path->changeLocalDirectory(Paths.get(path)));
        localTree.useContext("local",java.util.stream.Stream.concat(
                java.util.stream.StreamSupport.stream(java.nio.file.FileSystems.getDefault().getRootDirectories().spliterator(),false).map(Path::toString),
                Stream.of(localDirectory.toString())).distinct().toList());
        remoteTree=new com.runzhi.aishell.ui.DirectoryTree(io,()->{
            final var service=sshService; final String session=currentTabId;
            return path->{
                if(service==null || session==null) throw new IllegalStateException("No session");
                try { String canonical=service.canonicalDirectoryForTab(session,path); String directory=canonical==null?path:canonical;
                    return service.listFilesForTab(session,directory).stream().filter(this::isRemoteDirectory)
                        .filter(file->!file.getName().equals(".") && !file.getName().equals(".."))
                        .map(file->joinRemotePath(directory,file.getName())).toList(); }
                catch(Exception failure) { throw new IllegalStateException(failure); }
            };
        },path->{ pathField.setText(path); navigateRemotePath(); });
        remoteTree.useContext("none",List.of("/","~"));
        localTreeHost.getChildren().setAll(localTree); remoteTreeHost.getChildren().setAll(remoteTree);
        queueView=new com.runzhi.aishell.ui.TransferQueueView(transferQueueStore,this::retryEndpoint,
                job->submitRecoveredJobs(List.of(job)),count->transferQueueTab.setText(I18n.tr("fm.phase.queueCount",count)));
        queueHost.getChildren().setAll(queueView);
        fileModes.getSelectionModel().select(remoteBrowserTab);
        fileModes.getSelectionModel().selectedItemProperty().addListener((_,_,tab)->{updateDirectoryLabels(); if(queueView!=null) queueView.refresh();});
        for(TextField field:List.of(localPathField,pathField)) field.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,event->{
            if(event.getCode()==javafx.scene.input.KeyCode.ESCAPE) {
                field.setText(field==localPathField?localDirectory.toString():remoteDirectory()); event.consume();
            }
        });
        fileManagerArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,event->{
            if(event.isControlDown() && !event.isShiftDown() && event.getCode()==javafx.scene.input.KeyCode.L) {
                TextField field=fileModes.getSelectionModel().getSelectedItem()==localBrowserTab?localPathField:pathField;
                field.requestFocus(); field.selectAll(); event.consume();
            }
        });
        fileManagerArea.widthProperty().addListener((_,_,width)->{
            localDateColumn.setVisible(width.doubleValue()>=650); remoteDateColumn.setVisible(width.doubleValue()>=650);
            localTypeColumn.setVisible(width.doubleValue()>=500); remoteTypeColumn.setVisible(width.doubleValue()>=500);
        });
        updateDirectoryLabels();
    }

    /** Keep the navigator narrow in pixels when the drawer grows, while allowing divider dragging. */
    private void compactDirectorySplit(javafx.scene.control.SplitPane split,javafx.scene.layout.Region tree) {
        if(split==null) return;
        javafx.scene.control.SplitPane.setResizableWithParent(tree,false);
        boolean[] initialized={false};
        split.widthProperty().addListener((_,_,width)->{
            if(!initialized[0] && width.doubleValue()>0) {
                initialized[0]=true;
                split.setDividerPositions(Math.min(144,width.doubleValue()*0.24)/width.doubleValue());
            }
        });
    }
    @FXML private void localDetails() { details(localFileTable,localDirectory.toString()); }
    @FXML private void remoteDetails() { details(remoteFileTable,remoteDirectory()); }
    private void details(TableView<FileInfo> table,String parent) {
        FileInfo file=table.getSelectionModel().getSelectedItem(); if(file==null) return;
        Alert alert=new Alert(Alert.AlertType.INFORMATION,parent+"/"+file.getName()+"\n"+file.getSize()+"\n"+formatModifiedTime(file.getModifiedTime())+"\n"+formatPermission(file.getPermission()));
        alert.setTitle(I18n.tr("fm.phase.details")); alert.setHeaderText(null); alert.showAndWait();
    }
    private void ownJob(String id) {
        final var owner=activeTransferControl; ownedQueueJobs.add(id);
        if(currentTransferJobId==null) currentTransferJobId=id;
        transferQueueStore.registerCancel(id,()->{
            if(activeTransferControl==owner && id.equals(currentTransferJobId)) {
                owner.cancel(); Platform.runLater(()->{if(activeTransferControl==owner) cancelTransfer();});
            }
        });
    }

    /** 拖拽悬停时加到远程面板上的高亮样式类 */
    private static final String DROP_TARGET_STYLE = "drop-target";

    /** 远程列表的可见状态；空目录、加载中、未连接与失败必须可区分。 */
    private enum RemoteState { DISCONNECTED, LOADING, EMPTY, FAILED, READY }
    private Label remotePlaceholder;

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
        // 连接建立/重连会在面板仍收起时回调这里；目标标题必须同步，不能等展开才更新。
        refreshTargetDisplay();
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
        remotePlaceholder = new Label();
        remotePlaceholder.setWrapText(true);
        remotePlaceholder.setMaxWidth(420);
        remotePlaceholder.getStyleClass().add("file-placeholder");
        remoteFileTable.setPlaceholder(remotePlaceholder);
        remoteFileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        localFileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        setRemoteState(RemoteState.DISCONNECTED, I18n.tr("fm.remote.disconnected"));
        
        // 启用多选模式
        remoteFileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        localFileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        remoteFileTable.setRowFactory(table -> {
            TableRow<com.runzhi.aishell.ssh.domain.FileInfo> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && isRemoteDirectory(row.getItem())) {
                    pathField.setText(joinRemotePath(pathField.getText(), row.getItem().getName()));
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
                    changeLocalDirectory(next);
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
        
        // 初始化状态：未绑定会话时明确提示，而不是显示 Ready
        updateStatus(I18n.tr("fm.remote.disconnected"));
        
        initializeNavigation();
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
        localTypeColumn.setCellValueFactory(cell -> text(I18n.tr((cell.getValue().getPermission() & 0040000)!=0?"fm.phase.folder":"fm.phase.file")));
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
            String parent = localDirectory.toString();
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
        final var targetService = sshService;
        final String targetTab = currentTabId;
        final String parentPath = remoteDirectory();
        final int targetGeneration = loadGeneration.get();
        TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle(I18n.tr("fm.newfolder.title"));
            dialog.setHeaderText(I18n.tr("fm.newfolder.header"));
            dialog.setContentText(I18n.tr("fm.newfolder.content"));

        dialog.showAndWait().ifPresent(folderName -> {
            if (folderName.trim().isEmpty()) {
                        ControllerUtils.showWarningAlert(I18n.tr("fm.warn.title"), I18n.tr("fm.newfolder.name.empty"));
                return;
            }

            if (targetService == null || targetTab == null || targetGeneration != loadGeneration.get()) {
        showErrorAlert(I18n.tr("fm.newfolder.ssh.disconnected"));
                return;
            }
            com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
                try {
                    String remotePath = joinRemotePath(parentPath, folderName.trim());
                    targetService.createDirectoryForTab(targetTab, remotePath);
                    
                    Platform.runLater(() -> {
                        if (targetGeneration != loadGeneration.get()) return;
                updateStatus(I18n.tr("fm.newfolder.created", folderName));
        showInfoAlert(I18n.tr("fm.newfolder.success.title"), I18n.tr("fm.newfolder.success.content", folderName));
                        loadRemoteFiles();
                    });
                } catch (Exception e) {
                    log.error("Create folder error", e);
                    Platform.runLater(() -> {
                        if (targetGeneration != loadGeneration.get()) return;
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
        List<FileInfo> selectedLocalFiles = List.copyOf(localFileTable.getSelectionModel().getSelectedItems());
        List<FileInfo> selectedRemoteFiles = List.copyOf(remoteFileTable.getSelectionModel().getSelectedItems());
        final Path localParent = localDirectory;
        final String remoteParent = remoteDirectory();
        final var targetService = sshService;
        final String targetTab = currentTabId;
        final int targetGeneration = loadGeneration.get();
        
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
                if (targetGeneration != loadGeneration.get()) {
                    updateStatus(I18n.tr("fm.session.changed"));
                    return;
                }
                // 在后台线程执行删除
                com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
                    try {
                        for (FileInfo file : new ArrayList<>(selectedLocalFiles)) {
                            java.nio.file.Files.delete(localParent.resolve(file.getName()));
                        }
                        for (com.runzhi.aishell.ssh.domain.FileInfo file : new ArrayList<>(selectedRemoteFiles)) {
                            String remotePath = joinRemotePath(remoteParent, file.getName());
                            boolean directory = (file.getPermission() & 0040000) != 0;
                            targetService.deletePathForTab(targetTab, remotePath, directory);
                        }
                        
                        Platform.runLater(() -> {
                            if (targetGeneration != loadGeneration.get()) return;
        updateStatus(I18n.tr("fm.delete.done"));
        showInfoAlert(I18n.tr("fm.delete.done.title"), I18n.tr("fm.delete.done.content"));
                            loadLocalFiles(); // 重新加载本地文件列表
                            loadRemoteFiles(); // 重新加载远程文件列表
                        });
                    } catch (Exception e) {
                        log.error("Delete error", e);
                        Platform.runLater(() -> {
                            if (targetGeneration != loadGeneration.get()) return;
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
        Path requestedDirectory = requestedLocalDirectory==null?localDirectory:requestedLocalDirectory;
        final int generation=localGeneration.incrementAndGet();
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
                                            path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
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
                    if (generation!=localGeneration.get()) return;
                    localDirectory=requestedDirectory; requestedLocalDirectory=null; localFiles.setAll(loaded);
                    if(localPathField!=null) localPathField.setText(localDirectory.toString());
                    if(localTree!=null) localTree.reveal(localDirectory.toString());
                    updateDirectoryLabels();
                });
            } catch (Exception e) {
                log.error("Load local files error", e);
                Platform.runLater(() -> {
                    if(generation!=localGeneration.get()) return;
                    requestedLocalDirectory=null;
                    updateStatus(I18n.tr("fm.phase.navigationFailed",requestedDirectory,localDirectory));
                });
            }
        });
    }

    private void loadRemoteFiles() {
        if (disposed) return;
        if (sshService == null || currentTabId == null || currentTabId.isBlank()) {
            int noSessionGeneration = loadGeneration.incrementAndGet();
            remoteLoading = false;
            Platform.runLater(() -> {
                if (noSessionGeneration != loadGeneration.get()) return;
                if (remoteFiles != null) remoteFiles.clear();
                loadedRemotePath = null;
                setRemoteState(RemoteState.DISCONNECTED, I18n.tr("fm.remote.disconnected"));
                updateStatus(I18n.tr("fm.remote.disconnected"));
            });
            return;
        }
        if (!isCurrentTabConnected()) {
            int disconnectedGeneration = loadGeneration.incrementAndGet();
            Platform.runLater(() -> {
                if (disconnectedGeneration != loadGeneration.get()) return;
                if (remoteFiles != null) remoteFiles.clear();
                loadedRemotePath = null;
                remoteLoading = false;
                setRemoteState(RemoteState.DISCONNECTED, I18n.tr("fm.remote.disconnected"));
                updateStatus(I18n.tr("fm.ssh.disconnected"));
            });
            return;
        }
        if (!panelVisible) {
            return;
        }

        final String requestTabId = currentTabId;
        final var requestService = sshService;
        final String requestPath = pathField != null ? pathField.getText() : "~";
        final String requestKey = normalizePathKey(requestPath);
        final int generation = loadGeneration.incrementAndGet();
        remoteLoading = true;
        // 切到新路径时先清掉旧目录内容，避免加载期间把上一路径显示成当前内容。
        if (!requestKey.equals(normalizePathKey(loadedRemotePath)) && remoteFiles != null) {
            remoteFiles.clear();
            if (remoteFileTable != null) remoteFileTable.getSelectionModel().clearSelection();
        }
        setRemoteState(RemoteState.LOADING, I18n.tr("fm.remote.loading"));
        updateStatus(I18n.tr("fm.remote.loading"));

        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            try {
                if (generation != loadGeneration.get()) {
                    return;
                }
                String canonical=requestService.canonicalDirectoryForTab(requestTabId,requestPath);
                final String successfulPath=canonical==null?requestPath:canonical;
                ObservableList<com.runzhi.aishell.ssh.domain.FileInfo> files =
                        requestService.listFilesForTab(requestTabId, successfulPath);

                if (generation != loadGeneration.get()) {
                    return;
                }
                Platform.runLater(() -> {
                    if (!isCurrentLoad(generation, requestService, requestTabId)) return;
                    if (!isCurrentTabConnected()) {
                        if (remoteFiles != null) remoteFiles.clear();
                        loadedRemotePath = null;
                        remoteLoading = false;
                        setRemoteState(RemoteState.DISCONNECTED, I18n.tr("fm.remote.disconnected"));
                        updateStatus(I18n.tr("fm.ssh.disconnected"));
                        return;
                    }
                    if (remoteFiles != null) {
                        remoteFiles.clear();
                        if (files != null) remoteFiles.addAll(files);
                    }
                    loadedRemotePath = successfulPath;
                    if(pathField!=null && java.util.Objects.equals(pathField.getText(),requestPath)) pathField.setText(successfulPath);
                    if(remoteTree!=null) remoteTree.reveal(successfulPath);
                    updateDirectoryLabels();
                    refreshTargetDisplay();
                    remoteLoading = false;
                    boolean empty = files == null || files.isEmpty();
                    setRemoteState(empty ? RemoteState.EMPTY : RemoteState.READY,
                            empty ? I18n.tr("fm.remote.empty") : "");
                    updateStatus(empty ? I18n.tr("fm.remote.empty") : I18n.tr("fm.ready"));
                });
            } catch (Exception e) {
                Platform.runLater(()->{
                    if(!isCurrentLoad(generation, requestService, requestTabId)) return;
                    remoteLoading=false;
                    if (!isCurrentTabConnected()) {
                        if (remoteFiles != null) remoteFiles.clear();
                        loadedRemotePath = null;
                        setRemoteState(RemoteState.DISCONNECTED, I18n.tr("fm.remote.disconnected"));
                        updateStatus(I18n.tr("fm.ssh.disconnected"));
                        return;
                    }
                    setRemoteState(RemoteState.FAILED, I18n.tr("fm.remote.failed"));
                    updateStatus(I18n.tr("fm.phase.navigationFailed",requestPath,loadedRemotePath==null?"—":loadedRemotePath));
                });
            }
        });
    }

    /** 异步结果只有在仍绑定同一服务、同一标签且代际未过期时才允许落地。 */
    private boolean isCurrentLoad(int generation, com.runzhi.aishell.ssh.SSHService requestService,
                                  String requestTabId) {
        return generation == loadGeneration.get()
                && !disposed
                && requestService == sshService
                && java.util.Objects.equals(requestTabId, currentTabId);
    }

    private void setRemoteState(RemoteState state, String placeholderText) {
        if (remotePlaceholder != null) remotePlaceholder.setText(placeholderText);
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
        try { changeLocalDirectory(resolveLocalPathInput(localPathField.getText())); }
        catch(RuntimeException invalid) { updateStatus(I18n.tr("fm.phase.navigationFailed",localPathField.getText(),localDirectory)); }
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
        if (directory == null) return;
        requestedLocalDirectory = directory;
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
    private void refreshLocalList() { navigateLocalPath(); }

    @FXML
    private void navigateRemotePath() {
        String path = pathField.getText();
        String value=path==null || path.isBlank()?"/":path.trim().replace('\\','/');
        if(!value.startsWith("/") && !value.startsWith("~")) value=joinRemotePath(remoteDirectory(),value);
        pathField.setText(value);
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
        if (disposed) return;
        boolean changed = this.sshService != sshService || !java.util.Objects.equals(currentTabId, tabId);
        if (changed && activeTransferControl != null) {
            updateStatus(I18n.tr("fm.session.busy"));
            return;
        }
        if (changed) {
            boundTargetParams = null;
            if(currentTabId!=null && loadedRemotePath!=null) remotePaths.put(currentTabId,loadedRemotePath);
            loadGeneration.incrementAndGet();
            loadedRemotePath = null;
            remoteLoading = false;
            recoveryChecked = false;
            if (remoteFiles != null) remoteFiles.clear();
            if (remoteFileTable != null) remoteFileTable.getSelectionModel().clearSelection();
            if (pathField != null) pathField.setText("~");
            if(remoteTree!=null) remoteTree.useContext(tabId==null?"none":tabId,List.of("/","~"));
            setRemoteState(RemoteState.DISCONNECTED, I18n.tr("fm.remote.disconnected"));
        }
        if (this.sshService != sshService) {
            if (this.sshService != null) this.sshService.removeSessionStateListener(sessionStateListener);
            if (sshService != null) sshService.addSessionStateListener(sessionStateListener);
        }
        this.sshService = sshService;
        this.currentTabId = tabId;
        updateStandaloneControls();
        refreshTargetDisplay();
        updateDirectoryLabels();
        log.info("FileManagerPanelController 已设置 SSH 服务，Tab ID: {}", tabId);
        if (sshService != null && tabId != null) {
            String directory = sshService.getCurrentWorkingDirectoryForTab(tabId);
            if(tabId!=null && remotePaths.containsKey(tabId)) directory=remotePaths.get(tabId);
            if (directory != null && pathField != null && (changed || loadedRemotePath==null)) {
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

    /** 可读目标属性，供内嵌抽屉头与独立窗口绑定；不暴露内部 tab id。 */
    public javafx.beans.property.ReadOnlyStringProperty targetDisplayProperty() {
        return targetDisplay.getReadOnlyProperty();
    }

    /** 目标完整信息（连接名 + user@host:port），供 tooltip 使用。 */
    public javafx.beans.property.ReadOnlyStringProperty targetTooltipProperty() {
        return targetTooltip.getReadOnlyProperty();
    }

    public String getTargetDisplay() { return targetDisplay.get(); }

    public boolean isStandalone() { return standalone; }

    /**
     * 绑定独立窗口并固定其会话。已绑定目标的独立窗口忽略后续打开请求，避免跟着活动标签换会话。
     */
    public void bindStandaloneTarget(com.runzhi.aishell.ssh.SSHService service, String tabId) {
        if (standalone && currentTabId != null) return;
        setStandalone(true);
        setSshService(service, tabId);
        applyStandaloneTitle();
    }

    /** 独立窗口隐藏内嵌次级按钮，并显示完整会话目标行。 */
    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
        updateStandaloneControls();
        if (remoteSessionLabel != null) {
            remoteSessionLabel.setVisible(standalone);
            remoteSessionLabel.setManaged(standalone);
        }
        refreshTargetDisplay();
    }

    private void updateStandaloneControls() {
        if (openStandaloneButton == null) return;
        openStandaloneButton.setVisible(!standalone);
        openStandaloneButton.setManaged(!standalone);
    }

    /** 内嵌文件区的次级入口：捕获当前 tabId/服务，交给已有导航窗口缓存与生命周期管理。 */
    @FXML
    private void openStandaloneWindow() {
        if (standalone) return;
        if (sshService == null || currentTabId == null) {
            updateStatus(I18n.tr("fm.standalone.noTarget"));
            return;
        }
        final var service = sshService;
        final String tabId = currentTabId;
        String title = I18n.tr("main.fileManagerWindowTitle", targetDisplay.get());
        com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getNavigationManager()
                .openInNewWindow("file-manager-panel.fxml", title, "file-manager-panel.fxml:" + tabId, controller -> {
                    if (controller instanceof FileManagerPanelController fileManager) {
                        fileManager.bindStandaloneTarget(service, tabId);
                    }
                });
    }

    private void refreshTargetDisplay() {
        ConnectionParams params = sshService == null || currentTabId == null ? null
                : sshService.getTabConnectionParams(currentTabId);
        if (params != null) boundTargetParams = params;
        else params = boundTargetParams;
        String target = params == null ? I18n.tr("fm.target.none") : params.displayTarget();
        String tooltip = params == null ? target : targetTooltip(params);
        targetDisplay.set(target);
        targetTooltip.set(tooltip);
        if (remoteSessionLabel != null) {
            remoteSessionLabel.setText(I18n.tr("fm.remote.session", target));
            remoteSessionLabel.setTooltip(new Tooltip(tooltip));
            remoteSessionLabel.setVisible(standalone);
            remoteSessionLabel.setManaged(standalone);
        }
    }

    private static String targetTooltip(ConnectionParams params) {
        String endpoint = params.displayEndpoint();
        String name = params.getProfileName();
        return name == null || name.isBlank() || name.equals(endpoint) ? endpoint : name + " · " + endpoint;
    }

    private void applyStandaloneTitle() {
        if (!standalone || fileManagerArea == null || fileManagerArea.getScene() == null) return;
        if (fileManagerArea.getScene().getWindow() instanceof javafx.stage.Stage stage) {
            stage.setTitle(I18n.tr("main.fileManagerWindowTitle", targetDisplay.get()));
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
        final String transferTabId = currentTabId;
        final var transferService = sshService;
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
            remoteFilePaths.add(joinRemotePath(remoteDirectory(), fileInfo.getName()));
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

        if (transferService != sshService || !java.util.Objects.equals(transferTabId, currentTabId)) {
            updateStatus(I18n.tr("fm.session.changed"));
            return;
        }
        if (!beginTransfer(selectedCount, -1)) return;
        final var control = activeTransferControl;
        var queuedJob = enqueueTransfer(com.runzhi.aishell.ssh.TransferQueueStore.Direction.DOWNLOAD,
                transferService.getHostForTab(transferTabId), endpointPort(transferService, transferTabId), transferService.getUsernameForTab(transferTabId),
                remoteFilePaths, localDirPath, policy.name());
        if (queuedJob == null) return;
        activeTransferTask = transferExecutor.submit(() -> {
            try {
                if(!transferQueueStore.startJob(queuedJob.id())) { control.cancel(); throw new java.util.concurrent.CancellationException(); }
                updateProgress(I18n.tr("fm.download.starting", selectedCount), 0);
                
                // 调用 SSHService 的批量下载方法
                transferService.downloadFilesForTab(transferTabId, remoteFilePaths, localDirPath,
                        createProgressCallback(I18n.tr("fm.action.download"), control), policy, control);
                if (control.isCancelled()) transferQueueStore.markCancelled(queuedJob.id());
                else transferQueueStore.complete(queuedJob.id());
                
                Platform.runLater(() -> {
                    if (activeTransferControl != control) return;
                    updateStatus(I18n.tr("fm.download.done"));
                    showInfoAlert(I18n.tr("fm.download.done.title"), I18n.tr("fm.download.done.content", selectedCount, localDirPath));
                    loadLocalFiles(); // 重新加载本地文件列表
                    finishTransfer(control);
                });
            } catch (Exception e) {
                if(control.isCancelled()) transferQueueStore.markCancelled(queuedJob.id()); else transferQueueStore.markFailed(queuedJob.id());
                log.error("批量下载失败", e);
                Platform.runLater(() -> {
                    if (activeTransferControl != control) return;
                    showErrorAlert(I18n.tr("fm.download.failed", e.getMessage()));
                    updateStatus(I18n.tr("fm.download.failed.status"));
                    finishTransfer(control);
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
     * 打包上传：本地目录压成 zip 传到远端再解压，适合小文件很多的情况。
     */
    @FXML
    private void uploadPackedFolder() {
        final String transferTabId = currentTabId;
        final var transferService = sshService;
        if (!isCurrentTabConnected()) {
            showErrorAlert(I18n.tr("fm.upload.disconnected"));
            updateStatus(I18n.tr("fm.ssh.disconnected"));
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.tr("fm.packed.choose"));
        java.io.File initial = localDirectory.toFile();
        chooser.setInitialDirectory(initial.isDirectory()
                ? initial : new java.io.File(System.getProperty("user.home")));
        java.io.File folder;
        try {
            folder = chooser.showDialog(localPathField.getScene().getWindow());
        } catch (Exception e) {
            log.warn("打开目录选择器失败", e);
            return;
        }
        if (folder == null || !folder.isDirectory()) {
            return;
        }

        String remoteDirPath = remoteDirectory();
        com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy policy =
                chooseOverwritePolicy(I18n.tr("fm.collision.remote"));
        if (policy == null) {
            updateStatus(I18n.tr("fm.upload.cancelled"));
            return;
        }

        if (transferService != sshService || !java.util.Objects.equals(transferTabId, currentTabId)) {
            updateStatus(I18n.tr("fm.session.changed"));
            return;
        }
        if (!beginTransfer(1, -1)) return;
        final var control = activeTransferControl;
        activeTransferTask = transferExecutor.submit(() -> {
            try {
                updateProgress(I18n.tr("fm.packed.starting", folder.getName()), 0);
                boolean unpacked = transferService.uploadPackedFolderForTab(transferTabId,
                        folder.getAbsolutePath(), remoteDirPath,
                        createProgressCallback(I18n.tr("fm.action.upload"), control), policy, control);
                Platform.runLater(() -> {
                    if (activeTransferControl != control) return;
                    if (unpacked) {
                        showInfoAlert(I18n.tr("fm.packed.done.title"),
                                I18n.tr("fm.packed.done.content", folder.getName(), remoteDirPath));
                        updateStatus(I18n.tr("fm.upload.done"));
                    } else {
                        showInfoAlert(I18n.tr("fm.packed.noextract.title"),
                                I18n.tr("fm.packed.noextract.content"));
                        updateStatus(I18n.tr("fm.packed.noextract.status"));
                    }
                    loadRemoteFiles();
                    finishTransfer(control);
                });
            } catch (Exception e) {
                log.error("打包上传失败", e);
                Platform.runLater(() -> {
                    if (activeTransferControl != control) return;
                    showErrorAlert(I18n.tr("fm.upload.failed", e.getMessage()));
                    updateStatus(I18n.tr("fm.upload.failed.status"));
                    finishTransfer(control);
                });
            }
        });
    }

    /**
     * 上传公共入口：工具栏按钮上传与拖拽上传共用同一套队列、覆盖策略与进度回调。
     */
    private void submitUpload(List<String> localFilePaths, String action) {
        final String transferTabId = currentTabId;
        final var transferService = sshService;
        int selectedCount = localFilePaths.size();
        log.info("{}：准备上传 {} 个文件", action, selectedCount);

        // 获取远程目录路径
        String remoteDirPath = remoteDirectory();

        com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy policy =
                chooseOverwritePolicy(I18n.tr("fm.collision.remote"));
        if (policy == null) {
            updateStatus(I18n.tr("fm.upload.cancelled"));
            return;
        }

        if (transferService != sshService || !java.util.Objects.equals(transferTabId, currentTabId)) {
            updateStatus(I18n.tr("fm.session.changed"));
            return;
        }
        if (!beginTransfer(selectedCount, -1)) return;
        final var control = activeTransferControl;
        var queuedJob = enqueueTransfer(com.runzhi.aishell.ssh.TransferQueueStore.Direction.UPLOAD,
                transferService.getHostForTab(transferTabId), endpointPort(transferService, transferTabId), transferService.getUsernameForTab(transferTabId),
                localFilePaths, remoteDirPath, policy.name());
        if (queuedJob == null) return;
        activeTransferTask = transferExecutor.submit(() -> {
            try {
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

                if(control.isCancelled() || activeTransferControl!=control) throw new java.util.concurrent.CancellationException();
                progressTracker=new TransferProgressTracker((int)Math.max(1,fileLeafCount),totalKnown?totalBytes:-1);

                if(!transferQueueStore.startJob(queuedJob.id())) { control.cancel(); throw new java.util.concurrent.CancellationException(); }
                updateProgress(I18n.tr("fm.upload.starting", selectedCount), 0);
                
                // 调用 SSHService 的批量上传方法
                transferService.uploadFilesForTab(transferTabId, localFilePaths, remoteDirPath,
                        createProgressCallback(I18n.tr("fm.action.upload"), control), policy, control);
                if (control.isCancelled()) transferQueueStore.markCancelled(queuedJob.id());
                else transferQueueStore.complete(queuedJob.id());
                
                Platform.runLater(() -> {
                    if (activeTransferControl != control) return;
                    updateStatus(I18n.tr("fm.upload.done"));
                    showInfoAlert(I18n.tr("fm.upload.done.title"), I18n.tr("fm.upload.done.content", selectedCount, remoteDirPath));
                    loadRemoteFiles(); // 重新加载远程文件列表
                    finishTransfer(control);
                });
            } catch (Exception e) {
                if(control.isCancelled()) transferQueueStore.markCancelled(queuedJob.id()); else transferQueueStore.markFailed(queuedJob.id());
                log.error("批量上传失败", e);
                Platform.runLater(() -> {
                    if (activeTransferControl != control) return;
                    showErrorAlert(I18n.tr("fm.upload.failed", e.getMessage()));
                    updateStatus(I18n.tr("fm.upload.failed.status"));
                    finishTransfer(control);
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
        if (parent == null) return;
        changeLocalDirectory(parent);
    }

    private static String joinRemotePath(String directory, String name) {
        String base = directory == null || directory.isBlank() ? "/" : directory;
        return (base.endsWith("/") ? base : base + "/") + name;
    }

    /** Unknown legacy ports are retained but never offered on an assumed endpoint. */
    private static int endpointPort(com.runzhi.aishell.ssh.SSHService service, String tabId) {
        var profile = service == null ? null : service.getTabConnectionParams(tabId);
        return profile == null ? 0 : profile.getPort();
    }

    private void offerTransferRecovery() {
        if (sshService == null || currentTabId == null) return;
        final var service = sshService;
        final String session = currentTabId;
        int port = endpointPort(service, session);
        if (port <= 0) return;
        var jobs = transferQueueStore.recoverable(service.getHostForTab(session), port,
                service.getUsernameForTab(session));
        if (jobs.isEmpty()) {
            if (transferQueueStore.recoverable(service.getHostForTab(session), service.getUsernameForTab(session))
                    .stream().anyMatch(job -> job.port() == 0)) updateStatus(I18n.tr("fm.recover.unverified"));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.tr("fm.recover.content", jobs.size()), ButtonType.YES, ButtonType.NO);
        alert.setTitle(I18n.tr("fm.recover.title"));
        alert.setHeaderText(I18n.tr("fm.recover.header", service.getUsernameForTab(session),
                service.getHostForTab(session) + ":" + port));
        if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        if (service != sshService || !java.util.Objects.equals(session, currentTabId)) return;
        submitRecoveredJobs(jobs);
    }

    /** Resume an explicitly confirmed endpoint batch, with one visible batch cancel handle. */
    private void submitRecoveredJobs(List<com.runzhi.aishell.ssh.TransferQueueStore.Job> jobs) {
        if (jobs.isEmpty()) return;
        final var service = sshService;
        final String session = currentTabId;
        int port = endpointPort(service, session);
        if (jobs.stream().anyMatch(job -> job.port() <= 0 || job.port() != port
                || !job.host().equals(service.getHostForTab(session))
                || !job.username().equals(service.getUsernameForTab(session)))) {
            updateStatus(I18n.tr("fm.session.changed"));
            return;
        }
        if (!beginTransfer(jobs.size(), -1)) return;
        final var control = activeTransferControl;
        if (cancelTransferButton != null) cancelTransferButton.setText(I18n.tr("fm.recover.cancel"));
        for(var job:jobs) { transferQueueStore.markPending(job.id()); ownJob(job.id()); }
        activeTransferTask = transferExecutor.submit(() -> {
            boolean failed = false;
            try {
                for (var job : jobs) {
                    if (control.isCancelled()) break;
                    if(transferQueueStore.snapshot().stream().noneMatch(current->current.id().equals(job.id()) && current.status()!=com.runzhi.aishell.ssh.TransferQueueStore.Status.CANCELLED)) continue;
                    try {
                        currentTransferJobId=job.id();
                        if(!transferQueueStore.startJob(job.id())) continue;
                        var policy = com.runzhi.aishell.ssh.FileTransferService.OverwritePolicy.valueOf(job.policy());
                        if (job.direction() == com.runzhi.aishell.ssh.TransferQueueStore.Direction.UPLOAD) {
                            service.uploadFilesForTab(session, job.sources(), job.destination(),
                                    createProgressCallback(I18n.tr("fm.action.upload.resume"), control), policy, control);
                        } else {
                            service.downloadFilesForTab(session, job.sources(), job.destination(),
                                    createProgressCallback(I18n.tr("fm.action.download.resume"), control), policy, control);
                        }
                        if (control.isCancelled()) transferQueueStore.markCancelled(job.id());
                        else transferQueueStore.complete(job.id());
                    } catch (Exception e) {
                        failed = true;
                        if(control.isCancelled()) transferQueueStore.markCancelled(job.id()); else transferQueueStore.markFailed(job.id());
                        if (control.isCancelled()) break;
                    }
                }
            } finally {
                boolean anyFailed = failed;
                Platform.runLater(() -> {
                    if (activeTransferControl != control) return;
                    finishTransfer(control);
                    updateStatus(I18n.tr(anyFailed ? "fm.recover.batch.failed" : "fm.recover.done"));
                });
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
        requestRemoteRefresh(true);
    }

    private com.runzhi.aishell.ssh.FileTransferService.ProgressCallback createProgressCallback(String action, com.runzhi.aishell.ssh.FileTransferService.TransferControl owner) {
        return new com.runzhi.aishell.ssh.FileTransferService.ProgressCallback() {
            @Override
            public void onProgress(String fileName, long current, long total) {
                if (activeTransferControl != owner) return;
                TransferProgressTracker tracker = progressTracker;
                if (tracker != null) tracker.onProgress(fileName, current, total);
                renderTransferProgress(action, false);
            }

            @Override
            public void onError(String fileName, String errorMessage) {
                if (activeTransferControl == owner) updateStatus(I18n.tr("fm.transfer.failed", action, fileName, errorMessage));
            }

            @Override
            public void onComplete(String fileName) {
                if (activeTransferControl != owner) return;
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
            if (progressTracker != tracker) return;
            if(currentTransferJobId!=null) transferQueueStore.progress(currentTransferJobId,Math.round(percent*100)+"% · "+formatLocalSize(transferred));
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

    /** Persist before starting I/O; release the busy state if queue persistence fails. */
    private com.runzhi.aishell.ssh.TransferQueueStore.Job enqueueTransfer(
            com.runzhi.aishell.ssh.TransferQueueStore.Direction direction, String host, int port,
            String username, List<String> sources, String destination, String policy) {
        try {
            var job=transferQueueStore.enqueue(direction, host, port, username, sources, destination, policy);
            ownJob(job.id()); return job;
        } catch (RuntimeException e) {
            finishTransfer(activeTransferControl);
            updateStatus(I18n.tr("fm.transfer.queue.failed", e.getMessage()));
            return null;
        }
    }

    private boolean beginTransfer(int fileCount, long totalBytes) {
        if (activeTransferControl != null) {
            updateStatus(I18n.tr("fm.transfer.busy"));
            return false;
        }
        activeTransferControl = new com.runzhi.aishell.ssh.FileTransferService.TransferControl();
        progressTracker = new TransferProgressTracker(fileCount, totalBytes);
        lastProgressRenderNano = 0L;
        if (cancelTransferButton != null) {
            cancelTransferButton.setText(I18n.tr("fm.cancel.transfer"));
            cancelTransferButton.setVisible(true);
            cancelTransferButton.setManaged(true);
        }
        if (transferProgressBar != null) {
            transferProgressBar.setProgress(0);
            transferProgressBar.setVisible(true);
            transferProgressBar.setManaged(true);
        }
        return true;
    }

    private void finishTransfer(com.runzhi.aishell.ssh.FileTransferService.TransferControl owner) {
        if (activeTransferControl != owner) return;
        for(String id:ownedQueueJobs) transferQueueStore.clearCancel(id);
        ownedQueueJobs.clear();
        currentTransferJobId=null;
        activeTransferControl = null;
        if (queueView != null) queueView.refresh();
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
        if(currentTransferJobId!=null) transferQueueStore.markCancelled(currentTransferJobId);
        Future<?> task = activeTransferTask;
        if (task != null) task.cancel(true);
        finishTransfer(control);
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
    private static long[] directoryLayout(java.io.File directory) throws java.io.IOException {
        long[] result=new long[2];
        try(var paths=Files.walk(directory.toPath())) {
            var iterator=paths.iterator();
            while(!Thread.currentThread().isInterrupted() && iterator.hasNext()) {
                var attrs=Files.readAttributes(iterator.next(),BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if(attrs.isRegularFile()) { result[0]+=attrs.size(); result[1]++; }
            }
        }
        return result;
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
        if (disposed) return;
        disposed = true;
        loadGeneration.incrementAndGet();
        if (sshService != null) sshService.removeSessionStateListener(sessionStateListener);
        localGeneration.incrementAndGet();
        if(localTree!=null) localTree.close(); if(remoteTree!=null) remoteTree.close(); if(queueView!=null) queueView.close();
        for(String id:ownedQueueJobs) transferQueueStore.clearCancel(id);
        ownedQueueJobs.clear();
        com.runzhi.aishell.ssh.FileTransferService.TransferControl control = activeTransferControl;
        if (control != null) control.cancel();
        Future<?> task = activeTransferTask;
        if (task != null) task.cancel(true);
        transferExecutor.shutdownNow();
        activeTransferControl = null;
        activeTransferTask = null;
    }
}
