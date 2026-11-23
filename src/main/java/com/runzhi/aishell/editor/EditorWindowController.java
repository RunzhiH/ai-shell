/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.util.Cleanable;
import com.runzhi.aishell.ssh.FileTransferService;
import com.runzhi.aishell.ssh.SSHService;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 编辑器窗口：标签、工具栏、状态栏与快捷键（方案 §3.2 / §4.3）。
 *
 * <p>所有 SFTP I/O 都在单线程后台执行；每个文档同一时刻只允许一个保存任务。
 * Ctrl+S 保存、Ctrl+Shift+S 另存为、F5 重新加载、Ctrl+W 关闭标签。
 */
@Slf4j
public class EditorWindowController implements Initializable, Cleanable {

    @FXML private BorderPane root;
    @FXML private ToolBar toolBar;
    @FXML private TabPane tabPane;
    @FXML private Label statusPathLabel;
    @FXML private ComboBox<String> encodingCombo;
    @FXML private Label eolLabel;
    @FXML private Label sizeLabel;
    @FXML private Label readonlyLabel;
    @FXML private Label savedLabel;
    @FXML private Button saveButton;
    @FXML private Button reloadButton;
    @FXML private Button saveAsButton;
    @FXML private Button findButton;
    @FXML private MenuButton openExternalButton;
    @FXML private Button openTerminalDirButton;
    @FXML private javafx.scene.layout.HBox findBar;
    @FXML private javafx.scene.control.TextField findField;
    @FXML private javafx.scene.control.TextField replaceField;
    @FXML private javafx.scene.control.CheckBox findCaseCheck;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "editor-io");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, EditorTab> openTabs = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean updatingEncoding;

    private volatile SSHService sshService;
    private volatile String tabId;
    private volatile String sessionDisplay;
    private Consumer<String> openInTerminalHandler;
    private ExternalEditorService externalEditorService;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        encodingCombo.getItems().addAll("UTF-8", "GBK", "Big5", "UTF-16LE", "UTF-16BE", "ISO-8859-1");
        encodingCombo.setOnAction(event -> onEncodingChanged());

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> refreshChrome());

        // 外部编辑器二级菜单：每次展开刷新检测到的编辑器列表，选中即以该编辑器打开并记为首选
        openExternalButton.showingProperty().addListener((obs, wasShowing, showing) -> {
            if (showing) populateExternalEditorMenu();
        });
        populateExternalEditorMenu();

        // Ctrl+S 保存、Ctrl+Shift+S 另存为、Ctrl+F 查找、Ctrl+H 替换、F3 下一处、F5 重新加载、Ctrl+W 关闭标签
        KeyCodeCombination save = new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN);
        KeyCodeCombination saveAs = new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCodeCombination find = new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN);
        KeyCodeCombination replace = new KeyCodeCombination(KeyCode.H, KeyCombination.CONTROL_DOWN);
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (save.match(event)) { event.consume(); onSave(); }
            else if (saveAs.match(event)) { event.consume(); onSaveAs(); }
            else if (find.match(event)) { event.consume(); showFindBar(false); }
            else if (replace.match(event)) { event.consume(); showFindBar(true); }
            else if (event.getCode() == KeyCode.F3) { event.consume(); onFindNext(); }
            else if (event.getCode() == KeyCode.F5) { event.consume(); onReload(); }
            else if (event.getCode() == KeyCode.W && event.isControlDown()) { event.consume(); closeActiveTab(); }
            else if (event.getCode() == KeyCode.ESCAPE && findBar.isVisible()) { event.consume(); onFindClose(); }
        });

        // 窗口关闭前拦截：脏标签 三选一
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && newScene.getWindow() instanceof Stage stage) {
                stage.setOnCloseRequest(this::onWindowCloseRequest);
            }
        });

        refreshChrome();
    }

    // ==================== 绑定入口 ====================

    /** 绑定远端会话上下文（每个会话一个编辑器窗口，窗口内多标签）。 */
    public void bindRemote(SSHService service, String tabId) {
        this.sshService = service;
        this.tabId = tabId;
        this.sessionDisplay = sessionDisplay(service, tabId);
        Platform.runLater(() -> {
            if (root != null && root.getScene() != null
                    && root.getScene().getWindow() instanceof Stage stage) {
                stage.setTitle(I18n.tr("editor.title", sessionDisplay));
            }
        });
    }

    /** 设置「在终端打开所在目录」处理器（由文件面板注入）。 */
    public void setOpenInTerminalHandler(Consumer<String> handler) {
        this.openInTerminalHandler = handler;
        Platform.runLater(this::refreshChrome);
    }

    public SSHService sshService() { return sshService; }
    public String tabId() { return tabId; }

    /** 在后台打开远端文件；同一 remotePath 已打开则直接聚焦。 */
    public void openRemote(String remotePath) {
        SSHService service = sshService;
        if (service == null || tabId == null) {
            log.warn("Editor window not bound to a session, ignoring open request: {}", remotePath);
            return;
        }
        submitOpen(new RemoteEditorService(service, tabId), remotePath);
    }

    /** 在后台打开本地文件。 */
    public void openLocal(String localPath) {
        submitOpen(new LocalEditorService(), localPath);
    }

    private void submitOpen(EditorService service, String path) {
        EditorTab existing = openTabs.get(tabKeyOf(service, path));
        if (existing != null) {
            Platform.runLater(() -> tabPane.getSelectionModel().select(existing.tab()));
            return;
        }
        ioExecutor.submit(() -> {
            if (closed.get()) return;
            try {
                EditorDocument doc = service.open(path);
                Platform.runLater(() -> mountTab(service, doc));
            } catch (EditorService.EditorOpenException e) {
                Platform.runLater(() -> handleOpenFailure(service, path, e));
            } catch (Exception e) {
                log.error("Failed to open {} in editor", path, e);
                Platform.runLater(() -> errorAlert(I18n.tr("editor.open.failed"),
                        path + "\n" + e.getMessage()));
            }
        });
    }

    // ==================== 打开失败保护 ====================

    private void handleOpenFailure(EditorService service, String path, EditorService.EditorOpenException failure) {
        switch (failure.reason()) {
            case TOO_LARGE -> offerTooLargeOptions(service, path, failure.size());
            case DIRECTORY -> errorAlert(I18n.tr("editor.error.title"),
                    I18n.tr("editor.open.directory", path));
            case NOT_FOUND -> errorAlert(I18n.tr("editor.error.title"),
                    I18n.tr("editor.open.notfound", path));
            case PERMISSION_DENIED -> errorAlert(I18n.tr("editor.error.title"),
                    I18n.tr("editor.open.permission", path));
            case CONNECT_ERROR -> errorAlert(I18n.tr("editor.error.title"),
                    I18n.tr("editor.open.connect", path));
            default -> errorAlert(I18n.tr("editor.error.title"),
                    path + "\n" + failure.getMessage());
        }
    }

    /** 大文件保护：仍以只读打开 / 用外部编辑器打开 / 下载 / 取消（方案 §3.1）。 */
    private void offerTooLargeOptions(EditorService service, String path, long size) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(I18n.tr("editor.tooLarge.title"));
        alert.setHeaderText(I18n.tr("editor.tooLarge.message", formatSize(size),
                formatSize(EditorPreferences.maxInlineBytes())));
        ButtonType readonly = new ButtonType(I18n.tr("editor.tooLarge.readonly"), ButtonBar.ButtonData.YES);
        ButtonType external = new ButtonType(I18n.tr("editor.tooLarge.external"), ButtonBar.ButtonData.OTHER);
        ButtonType download = new ButtonType(I18n.tr("editor.tooLarge.download"), ButtonBar.ButtonData.HELP);
        ButtonType cancel = new ButtonType(I18n.tr("editor.conflict.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(readonly, external, download, cancel);
        Optional<ButtonType> chosen = alert.showAndWait();
        if (chosen.isEmpty() || chosen.get() == cancel) return;
        if (chosen.get() == readonly) {
            ioExecutor.submit(() -> {
                if (closed.get()) return;
                try {
                    EditorDocument doc = service.openReadOnly(path);
                    Platform.runLater(() -> mountTab(service, doc));
                } catch (Exception e) {
                    Platform.runLater(() -> errorAlert(I18n.tr("editor.open.failed"),
                            path + "\n" + e.getMessage()));
                }
            });
        } else if (chosen.get() == external) {
            openInExternalEditor(path, null);
        } else if (chosen.get() == download) {
            downloadRemoteFile(path);
        }
    }

    private void downloadRemoteFile(String remotePath) {
        if (sshService == null || tabId == null) return;
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.tr("fm.chooser.local.title"));
        Stage stage = windowOf(root);
        java.io.File target = chooser.showDialog(stage);
        if (target == null) return;
        String localDir = target.getAbsolutePath();
        ioExecutor.submit(() -> {
            try {
                sshService.downloadFilesForTab(tabId, List.of(remotePath), localDir, null);
                Platform.runLater(() -> {
                    savedLabel.setText(I18n.tr("editor.downloaded", remotePath, localDir));
                    savedLabel.setVisible(true);
                });
            } catch (Exception e) {
                log.error("Download from editor failed: {}", remotePath, e);
                Platform.runLater(() -> errorAlert(I18n.tr("fm.download.failed.status"),
                        I18n.tr("fm.download.failed", e.getMessage())));
            }
        });
    }

    // ==================== 标签装载 ====================

    private void mountTab(EditorService service, EditorDocument doc) {
        EditorTab existing = openTabs.get(doc.tabKey());
        if (existing != null) {
            tabPane.getSelectionModel().select(existing.tab());
            return;
        }
        EditorText area = EditorTextFactory.create(doc);
        area.styleClass().add("editor-text");

        Tab tab = new Tab();
        tab.setTooltip(new Tooltip(doc.displayPath()));
        EditorTab editorTab = new EditorTab(tab, area, doc, service);
        tab.setContent(area.node());
        tab.setUserData(editorTab);
        tab.setOnCloseRequest(event -> {
            event.consume();
            closeTab(editorTab);
        });
        tab.setOnClosed(event -> {
            openTabs.remove(doc.tabKey());
            refreshChrome();
        });
        editorTab.renderContent(TextCodec.normalizeForDisplay(displayText(doc)));
        // 会话恢复（方案 §4.5 editor.restoreSession）：本会话上次关闭时该路径若有未保存内容则回填为脏
        if (EditorPreferences.restoreSession()) {
            EditorSessionStore.Entry stored = takeStoredRestore(doc.tabKey());
            if (stored != null) {
                doc.setLoadedBytes(stored.content());
                area.setText(TextCodec.normalizeForDisplay(displayText(doc)));
                editorTab.markDirty();
            }
        }
        area.addTextChangeListener(() -> {
            editorTab.markDirty();
            refreshChrome();
        });
        openTabs.put(doc.tabKey(), editorTab);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        refreshChrome();

        if (doc.symlink()) {
            infoAlert(I18n.tr("editor.symlink.title"), I18n.tr("editor.symlink.message", doc.displayPath()));
        }
        if (doc.binary()) {
            infoAlert(I18n.tr("editor.binary.title"), I18n.tr("editor.binary.message", doc.displayName()));
        }
    }

    private static String displayText(EditorDocument doc) {
        byte[] bytes = doc.loadedBytes();
        if (bytes == null) return "";
        TextCodec.Detection detection = TextCodec.detect(bytes, doc.encoding());
        return detection.text();
    }

    private void closeTab(EditorTab editorTab) {
        if (editorTab == null) return;
        if (!editorTab.document().dirty()) {
            removeTab(editorTab);
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.tr("editor.dirty.title"));
        alert.setHeaderText(I18n.tr("editor.dirty.header", editorTab.document().displayName()));
        ButtonType save = new ButtonType(I18n.tr("editor.dirty.save"), ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType(I18n.tr("editor.dirty.discard"), ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType(I18n.tr("editor.conflict.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);
        Optional<ButtonType> chosen = alert.showAndWait();
        if (chosen.isEmpty() || chosen.get() == cancel) return;
        if (chosen.get() == discard) {
            removeTab(editorTab);
        } else {
            saveAndThen(editorTab, () -> {
                if (!editorTab.document().dirty()) removeTab(editorTab);
            });
        }
    }

    private void removeTab(EditorTab editorTab) {
        tabPane.getTabs().remove(editorTab.tab());
        openTabs.remove(editorTab.document().tabKey());
        refreshChrome();
    }

    private void closeActiveTab() {
        EditorTab active = activeTab();
        if (active != null) closeTab(active);
    }

    // ==================== 保存 / 重载 / 另存为 ====================

    @FXML
    void onSave() {
        EditorTab active = activeTab();
        if (active == null) return;
        if (active.document().readOnly()) {
            statusError(I18n.tr("editor.readonly.hint"));
            return;
        }
        saveAndThen(active, null);
    }

    private void saveAndThen(EditorTab editorTab, Runnable onSaved) {
        if (editorTab == null) return;
        EditorDocument doc = editorTab.document();
        if (doc.readOnly() || !doc.dirty()) {
            if (onSaved != null) Platform.runLater(onSaved);
            return;
        }
        if (!editorTab.startSave()) {
            statusError(I18n.tr("editor.saving.busy"));
            return;
        }
        saveButton.setDisable(true);
        final byte[] bytes = editorTab.currentBytes();
        ioExecutor.submit(() -> {
            if (closed.get()) {
                editorTab.finishSave();
                return;
            }
            try {
                editorTab.service().save(doc, bytes, false);
                Platform.runLater(() -> {
                    editorTab.markClean();
                    editorTab.document().setLoadedBytes(bytes);
                    savedLabel.setText(I18n.tr("editor.savedAt", nowDisplay()));
                    savedLabel.setVisible(true);
                    statusError(null);
                    refreshChrome();
                    if (onSaved != null) onSaved.run();
                });
            } catch (FileTransferService.RemoteFileChangedException conflict) {
                Platform.runLater(() -> showConflictDialog(editorTab, bytes));
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
                    Platform.runLater(() -> errorAlert(I18n.tr("editor.save.failed"),
                            I18n.tr("editor.save.permission", doc.displayPath())));
                } else {
                    Platform.runLater(() -> errorAlert(I18n.tr("editor.save.failed"),
                            I18n.tr("editor.save.sftp", e.getMessage())));
                }
            } catch (Exception e) {
                log.error("Failed to save {}", doc.displayPath(), e);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> statusError(I18n.tr("editor.save.error", message)));
            } finally {
                editorTab.finishSave();
                Platform.runLater(this::refreshChrome);
            }
        });
    }

    /** 冲突对话框：重新加载 / 覆盖 / 另存为 / 取消（方案 §3.4）。 */
    private void showConflictDialog(EditorTab editorTab, byte[] bytes) {
        EditorDocument doc = editorTab.document();
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(I18n.tr("editor.conflict.title"));
        alert.setHeaderText(I18n.tr("editor.conflict.message", doc.displayPath()));
        ButtonType reload = new ButtonType(I18n.tr("editor.conflict.reload"), ButtonBar.ButtonData.LEFT);
        ButtonType overwrite = new ButtonType(I18n.tr("editor.conflict.overwrite"), ButtonBar.ButtonData.YES);
        ButtonType saveAs = new ButtonType(I18n.tr("editor.conflict.saveAs"), ButtonBar.ButtonData.HELP);
        ButtonType cancel = new ButtonType(I18n.tr("editor.conflict.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(reload, overwrite, saveAs, cancel);
        Optional<ButtonType> chosen = alert.showAndWait();
        if (chosen.isEmpty() || chosen.get() == cancel) return;
        if (chosen.get() == reload) {
            doReload(editorTab);
        } else if (chosen.get() == overwrite) {
            forceSave(editorTab, bytes);
        } else {
            doSaveAs(editorTab);
        }
    }

    private void forceSave(EditorTab editorTab, byte[] bytes) {
        EditorDocument doc = editorTab.document();
        saveButton.setDisable(true);
        ioExecutor.submit(() -> {
            try {
                editorTab.service().save(doc, bytes, true);
                Platform.runLater(() -> {
                    editorTab.markClean();
                    savedLabel.setText(I18n.tr("editor.savedAt", nowDisplay()));
                    refreshChrome();
                });
            } catch (Exception e) {
                log.error("Forced save failed: {}", doc.displayPath(), e);
                Platform.runLater(() -> errorAlert(I18n.tr("editor.save.failed"), e.getMessage()));
            } finally {
                editorTab.finishSave();
                Platform.runLater(this::refreshChrome);
            }
        });
    }

    @FXML
    private void onReload() {
        EditorTab active = activeTab();
        if (active == null) return;
        doReload(active);
    }

    private void doReload(EditorTab editorTab) {
        if (editorTab == null) return;
        EditorDocument doc = editorTab.document();
        if (doc.dirty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(I18n.tr("editor.reload.title"));
            alert.setHeaderText(I18n.tr("editor.reload.confirm", doc.displayName()));
            Optional<ButtonType> chosen = alert.showAndWait();
            if (chosen.isEmpty() || chosen.get() != ButtonType.OK) return;
        }
        ioExecutor.submit(() -> {
            if (closed.get()) return;
            try {
                EditorDocument fresh = editorTab.service().reload(doc);
                Platform.runLater(() -> {
                    editorTab.replace(fresh, TextCodec.normalizeForDisplay(displayText(fresh)));
                    refreshChrome();
                });
            } catch (Exception e) {
                log.error("Reload failed: {}", doc.displayPath(), e);
                Platform.runLater(() -> errorAlert(I18n.tr("editor.reload.title"),
                        I18n.tr("editor.save.error", e.getMessage())));
            }
        });
    }

    @FXML
    private void onSaveAs() {
        EditorTab active = activeTab();
        if (active == null) return;
        doSaveAs(active);
    }

    private void doSaveAs(EditorTab editorTab) {
        if (editorTab == null) return;
        EditorDocument doc = editorTab.document();
        final byte[] bytes = editorTab.currentBytes();
        if (doc.isRemote() && sshService != null && tabId != null) {
            TextInputDialog dialog = new TextInputDialog(defaultSaveAsPath(doc));
            dialog.setTitle(I18n.tr("editor.saveAs"));
            dialog.setHeaderText(I18n.tr("editor.saveAs.remote.header"));
            dialog.setContentText(I18n.tr("editor.saveAs.remote.content"));
            Optional<String> chosen = dialog.showAndWait();
            if (chosen.isEmpty() || chosen.get().isBlank()) return;
            String target = chosen.get().trim();
            if (Objects.equals(target, doc.remotePath())) {
                saveAndThen(editorTab, null);
                return;
            }
            ioExecutor.submit(() -> {
                try {
                    if (sshService.remotePathExistsForTab(tabId, target)) {
                        boolean overwrite = confirmOverwrite(target);
                        if (!overwrite) return;
                    }
                    sshService.writeBytesForTab(tabId, target, bytes, 0);
                    EditorAudit.recordWrite(tabId, target, bytes, "save-as");
                    Platform.runLater(() -> {
                        statusError(null);
                        savedLabel.setText(I18n.tr("editor.savedAt", nowDisplay()));
                        openRemote(target);
                    });
                } catch (Exception e) {
                    log.error("Save-as failed: {}", target, e);
                    Platform.runLater(() -> errorAlert(I18n.tr("editor.save.failed"), e.getMessage()));
                }
            });
        } else if (doc.localPath() != null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.tr("editor.saveAs"));
            chooser.setInitialDirectory(doc.localPath().getParent() == null
                    ? null : doc.localPath().getParent().toFile());
            chooser.setInitialFileName(doc.displayName());
            java.io.File target = chooser.showSaveDialog(windowOf(root));
            if (target == null) return;
            ioExecutor.submit(() -> {
                try {
                    Files.write(target.toPath(), bytes);
                    EditorAudit.recordWrite("local", target.getAbsolutePath(), bytes, "save-as");
                    Platform.runLater(() -> {
                        statusError(null);
                        savedLabel.setText(I18n.tr("editor.savedAt", nowDisplay()));
                        openLocal(target.getAbsolutePath());
                    });
                } catch (Exception e) {
                    log.error("Save-as failed: {}", target, e);
                    Platform.runLater(() -> errorAlert(I18n.tr("editor.save.failed"), e.getMessage()));
                }
            });
        }
    }

    private boolean confirmOverwrite(String target) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.tr("editor.saveAs.exists.title"));
        alert.setHeaderText(I18n.tr("editor.saveAs.exists.header", target));
        Optional<ButtonType> chosen = alert.showAndWait();
        return chosen.isPresent() && chosen.get() == ButtonType.OK;
    }

    private static String defaultSaveAsPath(EditorDocument doc) {
        return doc.remotePath() != null ? doc.remotePath() : (doc.localPath() == null ? "" : doc.localPath().toString());
    }

    // ==================== 外部编辑器 / 终端 ====================

    private void populateExternalEditorMenu() {
        openExternalButton.getItems().clear();
        MenuItem auto = new MenuItem(I18n.tr("fm.edit.external.auto"));
        auto.setOnAction(event -> onOpenExternal());
        openExternalButton.getItems().addAll(auto, new SeparatorMenuItem());
        for (ExternalEditor editor : ExternalEditorDetector.detectAvailable()) {
            MenuItem item = new MenuItem(editor.name());
            item.setOnAction(event -> {
                EditorTab active = activeTab();
                if (active != null) openExternalForDocument(active.document(), editor);
            });
            openExternalButton.getItems().add(item);
        }
        MenuItem other = new MenuItem(I18n.tr("fm.edit.external.other"));
        other.setOnAction(event -> {
            ExternalEditor picked = pickExternalEditorProgram();
            if (picked == null) return;
            EditorTab active = activeTab();
            if (active != null) openExternalForDocument(active.document(), picked);
        });
        openExternalButton.getItems().addAll(new SeparatorMenuItem(), other);
    }

    @FXML
    private void onOpenExternal() {
        EditorTab active = activeTab();
        if (active == null) return;
        openExternalForDocument(active.document(), null);
    }

    /** 弹出文件选择器让用户挑选任意外部程序（远端文件同样走「临时文件+回传」流程）。 */
    private ExternalEditor pickExternalEditorProgram() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.tr("fm.edit.external.other"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(I18n.tr("fm.edit.external.programs"),
                        "*.exe", "*.cmd", "*.bat", "*.sh", "*"));
        java.io.File selected;
        try {
            Window window = openExternalButton.getScene() == null ? null : openExternalButton.getScene().getWindow();
            selected = chooser.showOpenDialog(window);
        } catch (Exception failure) {
            log.warn("External program picker failed", failure);
            return null;
        }
        if (selected == null || !selected.isFile()) return null;
        String fileName = selected.getName();
        int dot = fileName.lastIndexOf('.');
        return new ExternalEditor(dot > 0 ? fileName.substring(0, dot) : fileName,
                List.of(selected.getAbsolutePath(), "{file}"), ExternalEditor.Source.CUSTOM);
    }

    /** 用指定编辑器打开当前文档（null 表示自动选择；本机文档就地打开，无需回传）。 */
    private void openExternalForDocument(EditorDocument doc, ExternalEditor editor) {
        if (doc == null) return;
        if (doc.isRemote()) {
            if (sshService == null || tabId == null) return;
            ExternalEditorService external = externalEditorService();
            if (external == null) return;
            if (editor != null) EditorPreferences.setPreferredEditorName(editor.name());
            if (editor == null) external.openRemote(doc.remotePath());
            else if (editor.isSystemDefault()) external.openRemoteWithSystemDefault(doc.remotePath());
            else external.openRemoteWith(doc.remotePath(), editor);
        } else {
            Path local = doc.localPath();
            if (local == null) return;
            ExternalEditor target = editor == null ? ExternalEditorDetector.preferred() : editor;
            if (!target.isSystemDefault()) EditorPreferences.setPreferredEditorName(target.name());
            try {
                ExternalEditorService.openLocalFile(local, target);
            } catch (Exception failure) {
                log.warn("Failed to start external editor for {}", local, failure);
                statusError(I18n.tr("editor.external.notFound") + ": " + failure.getMessage());
            }
        }
    }

    /** 用指定编辑器打开当前文档（null 表示自动选择；系统默认走 openRemoteWithSystemDefault）。 */
    private void openInExternalEditor(String path, ExternalEditor editor) {
        if (sshService == null || tabId == null || path == null) return;
        ExternalEditorService external = externalEditorService();
        if (external == null) return;
        if (editor != null && !editor.isSystemDefault()) {
            EditorPreferences.setPreferredEditorName(editor.name());
            external.openRemoteWith(path, editor);
        } else if (editor != null) {
            external.openRemoteWithSystemDefault(path);
        } else {
            external.openRemote(path);
        }
    }

    ExternalEditorService externalEditorService() {
        if (externalEditorService == null) {
            SSHService service = sshService;
            if (service == null || tabId == null) return null;
            externalEditorService = new ExternalEditorService(service, tabId, new EditorInteraction(this::statusError));
        }
        return externalEditorService;
    }

    @FXML
    private void onOpenTerminalDir() {
        EditorTab active = activeTab();
        if (active == null || openInTerminalHandler == null) return;
        EditorDocument doc = active.document();
        String dir = doc.isRemote() ? parentOf(doc.remotePath())
                : (doc.localPath() != null && doc.localPath().getParent() != null
                        ? doc.localPath().getParent().toString() : null);
        if (dir != null) openInTerminalHandler.accept(dir);
    }

    // ==================== 编码切换 ====================

    private void onEncodingChanged() {
        if (updatingEncoding) return;
        EditorTab active = activeTab();
        if (active == null) return;
        EditorDocument doc = active.document();
        String chosen = encodingCombo.getValue();
        if (chosen == null || chosen.equals(doc.encoding())) return;
        byte[] bytes = doc.loadedBytes();
        if (bytes == null) return;
        try {
            Charset charset = Charset.forName(chosen);
            String decoded = decodeForSwitch(bytes, charset);
            if (decoded == null) {
                errorAlert(I18n.tr("editor.error.title"), I18n.tr("editor.encoding.failed", chosen));
                return;
            }
            EditorDocument replacement = cloneDocument(doc, chosen, decoded);
            active.replace(replacement, TextCodec.normalizeForDisplay(decoded));
            active.markDirty();
            refreshChrome();
        } catch (Exception e) {
            log.warn("Encoding switch to {} failed", chosen, e);
            errorAlert(I18n.tr("editor.error.title"), I18n.tr("editor.encoding.failed", chosen));
        }
    }

    private static String decodeForSwitch(byte[] bytes, Charset charset) {
        if (StandardCharsets.UTF_8.name().equals(charset.name()) && hasUtf8Bom(bytes)) {
            return TextCodec.decodeStrictly(bytes, 3, charset.name());
        }
        return TextCodec.decodeStrictly(bytes, 0, charset.name());
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
    }

    private static EditorDocument cloneDocument(EditorDocument doc, String encoding, String decoded) {
        EditorDocument clone = new EditorDocument(doc.sessionId(), doc.remotePath(), doc.localPath(),
                doc.displayName(), doc.language());
        clone.setEncoding(encoding);
        clone.setBom(hasUtf8Bom(doc.loadedBytes()) && "UTF-8".equals(encoding));
        clone.setEol(TextCodec.dominantEol(decoded));
        clone.setBinary(doc.binary());
        clone.setPreviewFallback(false);
        clone.setLoadedMtime(doc.loadedMtime());
        clone.setLoadedSize(doc.loadedSize());
        clone.setLoadedBytes(doc.loadedBytes());
        clone.setSymlink(doc.symlink());
        clone.setDirty(doc.dirty());
        clone.setReadOnly(doc.readOnly());
        return clone;
    }

    // ==================== 界面刷新 ====================

    private void refreshChrome() {
        EditorTab active = activeTab();
        boolean hasTab = active != null;
        boolean dirty = hasTab && active.document().dirty();
        boolean readOnly = hasTab && active.document().readOnly();
        saveButton.setDisable(!hasTab || readOnly || !dirty);
        reloadButton.setDisable(!hasTab);
        if (findButton != null) findButton.setDisable(!hasTab);
        saveAsButton.setDisable(!hasTab);
        openExternalButton.setDisable(!hasTab);
        openTerminalDirButton.setDisable(!hasTab || openInTerminalHandler == null);
        updateStatusBar(active);
    }

    private void updateStatusBar(EditorTab active) {
        if (active == null) {
            statusPathLabel.setText("");
            eolLabel.setText("");
            sizeLabel.setText("");
            readonlyLabel.setVisible(false);
            readonlyLabel.setManaged(false);
            return;
        }
        EditorDocument doc = active.document();
        statusPathLabel.setText(statusPathOf(doc));
        updatingEncoding = true;
        encodingCombo.setValue(doc.encoding());
        updatingEncoding = false;
        eolLabel.setText(doc.eol() == null ? "" : doc.eol().display());
        sizeLabel.setText(formatSize(doc.loadedSize()));
        if (doc.readOnly()) {
            readonlyLabel.setVisible(true);
            readonlyLabel.setManaged(true);
            readonlyLabel.setText(I18n.tr("editor.status.readonly")
                    + readOnlyReason(doc));
        } else {
            readonlyLabel.setVisible(false);
            readonlyLabel.setManaged(false);
        }
    }

    private static String readOnlyReason(EditorDocument doc) {
        if (doc.binary()) return "（" + I18n.tr("editor.readonly.binary") + "）";
        if (doc.previewFallback()) return "（" + I18n.tr("editor.readonly.encoding") + "）";
        return "（" + I18n.tr("editor.readonly.permission") + "）";
    }

    private String statusPathOf(EditorDocument doc) {
        if (doc.isRemote()) {
            String endpoint = sessionDisplay == null ? (tabId == null ? "" : tabId) : sessionDisplay;
            return endpoint + ":" + doc.remotePath();
        }
        return doc.displayPath();
    }

    private void statusError(String message) {
        savedLabel.setVisible(message != null);
        if (message != null) savedLabel.setText(message);
    }

    // ==================== 窗口生命周期 ====================

    private void onWindowCloseRequest(WindowEvent event) {
        for (EditorTab editorTab : List.copyOf(openTabs.values())) {
            if (editorTab.document().dirty()) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle(I18n.tr("editor.dirty.title"));
                alert.setHeaderText(I18n.tr("editor.dirty.header.all"));
                ButtonType saveAll = new ButtonType(I18n.tr("editor.dirty.saveAll"), ButtonBar.ButtonData.YES);
                ButtonType discard = new ButtonType(I18n.tr("editor.dirty.discard"), ButtonBar.ButtonData.NO);
                ButtonType cancel = new ButtonType(I18n.tr("editor.conflict.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(saveAll, discard, cancel);
                Optional<ButtonType> chosen = alert.showAndWait();
                if (chosen.isEmpty() || chosen.get() == cancel) {
                    event.consume();
                    return;
                }
                if (chosen.get() == discard) break;
                saveAllDirtyThenClose(event);
                return;
            }
        }
        shutdownExternalEditor();
    }

    private void saveAllDirtyThenClose(WindowEvent event) {
        List<EditorTab> dirty = openTabs.values().stream().filter(t -> t.document().dirty()).toList();
        if (dirty.isEmpty()) return;
        Stage stage = windowOf(root);
        for (EditorTab editorTab : dirty) {
            saveAndThen(editorTab, () -> {
                // 全部保存完成后真正关闭窗口（仍有脏则保留，让用户自行处理）
                if (openTabs.values().stream().noneMatch(tab -> tab.document().dirty())) {
                    Platform.runLater(() -> {
                        if (stage != null) stage.close();
                    });
                }
            });
        }
        event.consume();
    }

    @Override
    public void onWindowClosing() {
        if (!closed.compareAndSet(false, true)) return;
        // 未保存的标签落盘，下次打开同一会话时恢复；全部干净则删除旧记录
        List<EditorSessionStore.Entry> entries = new ArrayList<>();
        for (EditorTab editorTab : openTabs.values()) {
            if (editorTab.document().dirty()) {
                EditorDocument doc = editorTab.document();
                entries.add(new EditorSessionStore.Entry(doc.isRemote(), doc.displayPath(),
                        editorTab.currentBytes()));
            }
        }
        EditorSessionStore.save(sessionKey(), entries);
        shutdownExternalEditor();
        ioExecutor.shutdownNow();
        openTabs.clear();
    }

    private void shutdownExternalEditor() {
        try {
            if (externalEditorService != null) externalEditorService.close();
        } catch (Exception e) {
            log.warn("External editor shutdown failed", e);
        }
    }

    /** 会话恢复身份：远端为 tabId，本地窗口固定 "local"。 */
    private String sessionKey() {
        return tabId != null ? tabId : LocalEditorService.SESSION_ID;
    }

    /** 从会话存储中按 tabKey 取出（并移除）一条未保存内容；无则返回 null。 */
    private EditorSessionStore.Entry takeStoredRestore(String tabKey) {
        String key = sessionKey();
        List<EditorSessionStore.Entry> entries = EditorSessionStore.load(key);
        for (EditorSessionStore.Entry entry : entries) {
            String entryTabKey = (entry.remote() ? key : LocalEditorService.SESSION_ID) + "|" + entry.path();
            if (entryTabKey.equals(tabKey)) {
                List<EditorSessionStore.Entry> remaining = new ArrayList<>(entries);
                remaining.remove(entry);
                EditorSessionStore.save(key, remaining);
                return entry;
            }
        }
        return null;
    }

    // ==================== 查找替换 ====================

    @FXML
    private void onFindToggle() {
        showFindBar(false);
    }

    private void showFindBar(boolean focusReplace) {
        findBar.setVisible(true);
        findBar.setManaged(true);
        (focusReplace ? replaceField : findField).requestFocus();
        refreshChrome();
    }

    @FXML
    private void onFindClose() {
        findBar.setVisible(false);
        findBar.setManaged(false);
        EditorTab active = activeTab();
        if (active != null) active.editor().requestFocus();
        refreshChrome();
    }

    @FXML
    void onFindNext() {
        find(true);
    }

    /** 测试钩子：设置查找内容并显示查找栏。 */
    void setFindText(String text) {
        findBar.setVisible(true);
        findBar.setManaged(true);
        findField.setText(text);
    }

    /** 测试钩子：设置替换内容。 */
    void setReplaceText(String text) {
        replaceField.setText(text);
    }

    @FXML
    private void onFindPrev() {
        find(false);
    }

    private void find(boolean forward) {
        EditorTab active = activeTab();
        if (active == null) return;
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            statusError(I18n.tr("editor.find.prompt"));
            return;
        }
        EditorText area = active.editor();
        String hay = area.getText();
        boolean matchCase = findCaseCheck.isSelected();
        int caret = area.getCaretPosition();
        int index = forward
                ? indexOf(hay, needle, caret, matchCase)
                : lastIndexOf(hay, needle, caret, matchCase);
        if (index < 0) {
            // 回绕：从另一端再来一遍
            index = forward
                    ? indexOf(hay, needle, 0, matchCase)
                    : lastIndexOf(hay, needle, hay.length(), matchCase);
        }
        if (index < 0) {
            statusError(I18n.tr("editor.find.noMore"));
            return;
        }
        area.selectRange(index, index + needle.length());
        area.requestFocus();
        statusError(I18n.tr("editor.find.count", countMatches(hay, needle, matchCase)));
    }

    @FXML
    private void onReplace() {
        EditorTab active = activeTab();
        if (active == null) return;
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) return;
        String replacement = replaceField.getText() == null ? "" : replaceField.getText();
        EditorText area = active.editor();
        boolean matchCase = findCaseCheck.isSelected();
        String selected = area.getSelectedText();
        boolean selectionMatches = matchCase
                ? needle.equals(selected)
                : needle.equalsIgnoreCase(selected);
        if (selectionMatches) {
            area.replaceSelection(replacement);
            active.markDirty();
            refreshChrome();
        }
        find(true);
    }

    @FXML
    void onReplaceAll() {
        EditorTab active = activeTab();
        if (active == null) return;
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) return;
        String replacement = replaceField.getText() == null ? "" : replaceField.getText();
        EditorText area = active.editor();
        String text = area.getText();
        boolean matchCase = findCaseCheck.isSelected();
        StringBuilder result = new StringBuilder();
        int count = 0;
        int start = 0;
        int index = indexOf(text, needle, 0, matchCase);
        while (index >= 0) {
            result.append(text, start, index).append(replacement);
            start = index + needle.length();
            count++;
            index = indexOf(text, needle, start, matchCase);
        }
        result.append(text, start, text.length());
        if (count > 0) {
            area.setText(result.toString());
            active.markDirty();
            refreshChrome();
        }
        statusError(I18n.tr("editor.replaced", count));
    }

    private static int indexOf(String hay, String needle, int from, boolean matchCase) {
        int max = hay.length() - needle.length();
        if (max < 0) return -1;
        for (int i = Math.max(0, from); i <= max; i++) {
            if (regionEquals(hay, i, needle, matchCase)) return i;
        }
        return -1;
    }

    private static int lastIndexOf(String hay, String needle, int from, boolean matchCase) {
        int max = hay.length() - needle.length();
        if (max < 0) return -1;
        int start = Math.min(from, max);
        for (int i = start; i >= 0; i--) {
            if (regionEquals(hay, i, needle, matchCase)) return i;
        }
        return -1;
    }

    private static boolean regionEquals(String hay, int offset, String needle, boolean matchCase) {
        for (int j = 0; j < needle.length(); j++) {
            char left = hay.charAt(offset + j);
            char right = needle.charAt(j);
            if (matchCase ? left != right : Character.toLowerCase(left) != Character.toLowerCase(right)) {
                return false;
            }
        }
        return true;
    }

    private static int countMatches(String hay, String needle, boolean matchCase) {
        int count = 0;
        int index = indexOf(hay, needle, 0, matchCase);
        while (index >= 0) {
            count++;
            index = indexOf(hay, needle, index + needle.length(), matchCase);
        }
        return count;
    }

    // ==================== 工具 ====================

    private EditorTab activeTab() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null) return null;
        return (EditorTab) selected.getUserData();
    }

    private static String tabKeyOf(EditorService service, String path) {
        return (service == null ? "none" : service.sessionId()) + "|" + path;
    }

    private static String parentOf(String remotePath) {
        if (remotePath == null) return null;
        int slash = remotePath.lastIndexOf('/');
        if (slash <= 0) return "/";
        return remotePath.substring(0, slash);
    }

    private static String sessionDisplay(SSHService service, String tabId) {
        if (service == null || tabId == null) return "";
        try {
            String host = service.getHostForTab(tabId);
            String user = service.getUsernameForTab(tabId);
            if (host != null && user != null) return user + "@" + host;
        } catch (Exception ignored) {
            // 展示信息失败不影响功能
        }
        return tabId;
    }

    private static Stage windowOf(javafx.scene.Node node) {
        if (node == null || node.getScene() == null) return null;
        return (Stage) node.getScene().getWindow();
    }

    private void errorAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        if (root != null && root.getScene() != null) alert.initOwner(windowOf(root));
        alert.showAndWait();
    }

    private void infoAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String nowDisplay() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }
}
