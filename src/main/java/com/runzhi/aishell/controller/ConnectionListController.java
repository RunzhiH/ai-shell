/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.util.ControllerUtils;
import com.runzhi.aishell.common.connection.ConnectionImportExportUtil;
import com.runzhi.aishell.common.config.ConfigManager;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class ConnectionListController extends BaseController {

    @FXML
    private TableView<ConnectionViewModel> connectionTable;
    @FXML
    private TableColumn<ConnectionViewModel, String> nameColumn;
    @FXML
    private TableColumn<ConnectionViewModel, String> hostColumn;
    @FXML
    private TableColumn<ConnectionViewModel, String> portColumn;
    @FXML
    private TableColumn<ConnectionViewModel, String> usernameColumn;
    @FXML
    private TableColumn<ConnectionViewModel, String> protocolColumn;
    @FXML
    private TableColumn<ConnectionViewModel, String> lastUsedColumn;
    @FXML
    private TableColumn<ConnectionViewModel, String> statusColumn;
    
    // 工具栏按钮
    @FXML
    private Button importButton;
    @FXML
    private Button exportButton;
    
    // 搜索功能
    @FXML
    private TextField searchField;
    
    // 统计信息标签
    @FXML
    private Label totalCountLabel;
    
    @FXML
    private Label connectedCountLabel;
    
    @FXML
    private Label disconnectedCountLabel;
    
    @FXML
    private Label lastUpdatedLabel;

    private SSHService sshService;

    private ObservableList<ConnectionViewModel> connections;

    public void initialize() {
        setupTableColumns();
        loadConnections();
        
        // 添加双击连接功能
        setupDoubleClickConnection();
        
        // 初始化导入导出按钮
        setupImportExportButtons();
        
        // 设置搜索功能
        setupSearchFunctionality();
    }
    
    private void setupSearchFunctionality() {
        if (searchField != null) {
            searchField.textProperty().addListener((observable, oldValue, newValue) -> {
                filterConnections(newValue);
            });
        }
    }
    
    private void filterConnections(String searchText) {
        if (searchText == null || searchText.trim().isEmpty()) {
            connectionTable.setItems(connections);
            return;
        }
        
        ObservableList<ConnectionViewModel> filteredList = FXCollections.observableArrayList();
        for (ConnectionViewModel conn : connections) {
            if (conn.getName().getValueSafe().toLowerCase().contains(searchText.toLowerCase()) ||
                conn.getHost().getValueSafe().toLowerCase().contains(searchText.toLowerCase()) ||
                conn.getUsername().getValueSafe().toLowerCase().contains(searchText.toLowerCase())) {
                filteredList.add(conn);
            }
        }
        
        connectionTable.setItems(filteredList);
    }

    private void setupDoubleClickConnection() {
        connectionTable.setRowFactory(tv -> {
            TableRow<ConnectionViewModel> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    log.info("Double click detected on connection: {}", row.getItem().getName());
                    ConnectionViewModel rowData = row.getItem();
                    handleConnectionByModel(rowData);
                    event.consume(); // 防止事件冒泡，避免冲突
                }
            });
            return row;
        });
    }
    
    private void setupImportExportButtons() {
        // 设置导入按钮事件
        if (importButton != null) {
            importButton.setOnAction(e -> handleImportConnections());
        }
        
        // 设置导出按钮事件
        if (exportButton != null) {
            exportButton.setOnAction(e -> handleExportConnections());
        }
    }
    
    @FXML
    public void handleImportConnections() {
        Window owner = connectionTable != null && connectionTable.getScene() != null
                ? connectionTable.getScene().getWindow() : null;
        List<ConnectionParams> imported = ConnectionImportExportUtil.showImportFileChooser(owner);
        if (imported.isEmpty()) return;

        List<ConnectionParams> merged = ConnectionImportExportUtil.mergeConnections(
                com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs(), imported);
        if (ConfigManager.getInstance().saveConnectionConfigs(merged)) {
            loadConnections();
            log.info("已导入并保存 {} 条连接配置", imported.size());
        } else {
            ControllerUtils.showErrorAlert("导入失败", "无法保存导入的连接配置");
        }
    }

    @FXML
    public void handleExportConnections() {
        Window owner = connectionTable != null && connectionTable.getScene() != null
                ? connectionTable.getScene().getWindow() : null;
        ConnectionImportExportUtil.showExportFileChooser(owner, com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs());
    }

    private void handleConnectionByModel(ConnectionViewModel connectionModel) {
        if (connectionModel == null) {
            ControllerUtils.showInfoAlert("提示", "连接信息无效");
            return;
        }
    
        log.info("=== handleConnectionByModel CALLED === Connection: {}", connectionModel.getName());
            
        // 根据显示名称找到对应的连接参数并连接
        List<ConnectionParams> allConnections = com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();
        log.info("Total connections loaded: {}", allConnections.size());
            
        for (ConnectionParams conn : allConnections) {
            String connName = com.runzhi.aishell.ssh.SSHConnectionManager.buildDisplayName(conn);
            String profileName = conn.getProfileName();
            String modelName = connectionModel.getName().get(); // 获取 StringProperty 的实际值
            log.info("Comparing: '{}' with '{}'", connName, modelName);
            // 列表可能显示配置名称或回退的 host:port - user，两者都要能匹配
            if (connName.equals(modelName) || (profileName != null && profileName.equals(modelName))) {
                log.info("✓ Found matching connection: {}:{}", conn.getHost(), conn.getPort());
                    
                // 更新连接状态和最后使用时间
                conn.setConnected(true);
                conn.setLastUsed(LocalDateTime.now());
                    
                // 直接通过参数管理器传递连接参数
                // TerminalPanelController 会监听 connection_params 并创建 Tab
                log.debug("Setting connection_params in parameter manager: {}:{}", conn.getHost(), conn.getPort());
                com.runzhi.aishell.common.util.ParameterManager.getInstance().put("connection_params", conn, 30000); // 30 秒超时
                log.debug("✓ connection_params set successfully");
                    
                // 通知主视图切换到终端面板。TerminalPanelController 消费 connection_params
                // 后会 remove 该参数，因此不会重复建 Tab；但不发此信号会导致用户停在连接列表页、
                // 终端面板从未初始化、连接参数无人消费，用户得不到任何反馈
                com.runzhi.aishell.common.util.ParameterManager.getInstance().put("switch_to_terminal", true, 5000);
                break;
            }
        }
            
        log.info("=== handleConnectionByModel COMPLETED ===");
    }

    private void setupTableColumns() {
        nameColumn.setCellValueFactory(cellData -> cellData.getValue().nameProperty());
        hostColumn.setCellValueFactory(cellData -> cellData.getValue().hostProperty());
        portColumn.setCellValueFactory(cellData -> cellData.getValue().portProperty());
        usernameColumn.setCellValueFactory(cellData -> cellData.getValue().usernameProperty());
        protocolColumn.setCellValueFactory(cellData -> cellData.getValue().protocolProperty());
        lastUsedColumn.setCellValueFactory(cellData -> cellData.getValue().lastUsedProperty());
        statusColumn.setCellValueFactory(cellData -> cellData.getValue().statusProperty());
    }

    public void loadConnections() {
        // 使用模块管理器加载连接配置
        List<ConnectionParams> connectionParamsList = com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();
        connections = FXCollections.observableArrayList();

        for (int i = 0; i < connectionParamsList.size(); i++) {
            ConnectionParams params = connectionParamsList.get(i);
            // 优先显示用户起的配置名称，没有时回退为 host:port - user
            String name = (params.getProfileName() != null && !params.getProfileName().isBlank())
                    ? params.getProfileName()
                    : com.runzhi.aishell.ssh.SSHConnectionManager.buildDisplayName(params);
            connections.add(new ConnectionViewModel(
                    name,
                    params.getHost(),
                    String.valueOf(params.getPort()),
                    params.getUsername(),
                    "SSH", // 默认协议为SSH，因为ConnectionParams类中没有getProtocol方法
                    formatLastUsedTime(params.getLastUsed()),
                    params.isConnected() ? "已连接" : "未连接"
            ));
        }

        connectionTable.setItems(connections);
        updateStatistics();
    }

    @FXML
    private void handleNewConnection() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/new-connection-config-dialog.fxml"));
            loader.setResources(I18n.bundle());
            Parent root = loader.load();

            // 获取控制器并设置SSH服务
            NewConnectionConfigController newController = loader.getController();
            if (newController != null) {
                newController.setSshService((com.runzhi.aishell.ssh.impl.SSHServiceImpl) sshService);
                newController.setRefreshCallback(this::loadConnections); // 设置刷新回调
            }

            Stage dialogStage = new Stage();
            dialogStage.setTitle("新建连接配置");
            dialogStage.initOwner(connectionTable.getScene().getWindow()); // 设置父窗口
            dialogStage.setScene(new Scene(root));
            var themeManager = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            dialogStage.getScene().getStylesheets().setAll(
                    themeManager.getStylesheetsFor(themeManager.getCurrentTheme()));

            if (newController != null) {
                newController.setDialogStage(dialogStage);
            }

            dialogStage.showAndWait();

            // 重新加载连接列表
            loadConnections();
        } catch (IOException e) {
            log.error("Failed to load new connection config dialog", e);
            ControllerUtils.showErrorAlert("错误", "加载新建连接配置对话框失败：" + e.getMessage());
        }
    }

    @FXML
    private void handleEditConnection() {
        ConnectionViewModel selectedConnection = connectionTable.getSelectionModel().getSelectedItem();
        if (selectedConnection == null) {
            ControllerUtils.showInfoAlert("提示", "请选择一个连接进行编辑");
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/new-connection-config-dialog.fxml"));
            loader.setResources(I18n.bundle());
            Parent root = loader.load();

            NewConnectionConfigController controller = loader.getController();
            if (controller != null) {
                controller.setSshService((com.runzhi.aishell.ssh.impl.SSHServiceImpl) sshService);
                
                // 查找并加载所选连接的配置
                List<ConnectionParams> allConnections = com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();
                for (ConnectionParams params : allConnections) {
                    String displayName = com.runzhi.aishell.ssh.SSHConnectionManager.buildDisplayName(params);
                    String profileName = params.getProfileName();
                    String selName = selectedConnection.getName().get();
                    if (displayName.equals(selName) || (profileName != null && profileName.equals(selName))) {
                        // 加载现有配置到编辑器
                        controller.loadExistingConfig(params);
                        break;
                    }
                }
                
                controller.setRefreshCallback(this::loadConnections); // 设置刷新回调
            }

            Stage dialogStage = new Stage();
            dialogStage.setTitle("编辑连接配置");
            dialogStage.initOwner(connectionTable.getScene().getWindow());
            dialogStage.setScene(new Scene(root));
            var themeManager = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            dialogStage.getScene().getStylesheets().setAll(
                    themeManager.getStylesheetsFor(themeManager.getCurrentTheme()));

            if (controller != null) {
                controller.setDialogStage(dialogStage);
            }

            dialogStage.showAndWait();

            // 重新加载连接列表
            loadConnections();
        } catch (IOException e) {
            log.error("Failed to load edit connection config dialog", e);
            ControllerUtils.showErrorAlert("错误", "加载编辑连接配置对话框失败：" + e.getMessage());
        }
    }

    @FXML
    private void handleDeleteConnection() {
        ConnectionViewModel selectedConnection = connectionTable.getSelectionModel().getSelectedItem();
        if (selectedConnection == null) {
            ControllerUtils.showInfoAlert("提示", "请选择一个连接进行删除");
            return;
        }

        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("确认删除");
        confirmDialog.setHeaderText("确认删除连接");
        confirmDialog.setContentText("您确定要删除连接 '" + selectedConnection.getName() + "' 吗？此操作不可撤销。");

        Optional<ButtonType> result = confirmDialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // 从模块管理器中删除连接配置
            List<ConnectionParams> allConnections = com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();
            
            for (ConnectionParams conn : allConnections) {
                String connName = com.runzhi.aishell.ssh.SSHConnectionManager.buildDisplayName(conn);
                String profileName = conn.getProfileName();
                String selName = selectedConnection.getName().get();
                if (connName.equals(selName) || (profileName != null && profileName.equals(selName))) {
                    boolean deleted = com.runzhi.aishell.common.config.ConfigManager.getInstance().removeConnectionConfig(conn);
                    if (deleted) {
                        loadConnections(); // 重新加载连接列表
                        ControllerUtils.showInfoAlert("操作成功", "连接配置删除成功！");
                    } else {
                        ControllerUtils.showErrorAlert("错误", "连接配置删除失败！");
                    }
                    break;
                }
            }
        }
    }

    @FXML
    private void handleConnect() {
        ConnectionViewModel selectedConnection = connectionTable.getSelectionModel().getSelectedItem();
        if (selectedConnection == null) {
            ControllerUtils.showInfoAlert("提示", "请选择一个连接进行连接");
            return;
        }

        handleConnectionByModel(selectedConnection);
    }
    
    @FXML
    private void handleTestConnection() {
        ConnectionViewModel selectedConnection = connectionTable.getSelectionModel().getSelectedItem();
        if (selectedConnection == null) {
            ControllerUtils.showInfoAlert("提示", "请选择一个连接进行测试");
            return;
        }

        // 先给用户即时反馈，避免误以为点击无效
        String hostLine = selectedConnection.getHost() != null
                ? selectedConnection.getHost().get() : "";
        updateStatusForTest("正在测试 " + hostLine + " ...");

        // 在后台线程测试连接
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().submitIoTask(() -> {
            try {
                // 根据显示名称找到对应的连接参数并测试连接
                List<ConnectionParams> allConnections = com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();

                for (ConnectionParams conn : allConnections) {
                    String connName = com.runzhi.aishell.ssh.SSHConnectionManager.buildDisplayName(conn);
                    String profileName = conn.getProfileName();
                    // 列表显示名优先使用 profileName，匹配必须双分支齐全，否则所有已命名连接都测不了（2026-10-23 审计 P1-4）
                    if (connName.equals(selectedConnection.getName().get())
                            || (profileName != null && profileName.equals(selectedConnection.getName().get()))) {
                        // 【修复】原来只 new 了对象从不 connect()，isConnected() 恒为 false，
                        // 却无条件弹"测试成功"。必须真正建立连接再判定
                        SSHConnectionManager tempConnection = new SSHConnectionManager(conn);
                        try {
                            tempConnection.connect();
                            boolean ok = tempConnection.isConnected();
                            Platform.runLater(() -> {
                                if (ok) {
                                    updateStatusForTest("测试成功: " + hostLine);
                                    ControllerUtils.showInfoAlert("测试成功",
                                            "成功连接到 " + hostLine + ":" + conn.getPort());
                                } else {
                                    updateStatusForTest("测试失败: " + hostLine);
                                    ControllerUtils.showErrorAlert("测试失败",
                                            "无法连接到 " + hostLine + ":" + conn.getPort()
                                                    + "\n请检查网络或服务器状态");
                                }
                            });
                            return null;
                        } finally {
                            // 无论成功失败都关闭临时连接
                            if (tempConnection.isConnected()) {
                                tempConnection.disconnect();
                            }
                        }
                    }
                }

                Platform.runLater(() -> {
                    ControllerUtils.showErrorAlert("错误", "找不到对应的连接配置，测试失败！");
                });
            } catch (Exception e) {
                log.error("连接测试失败", e);
                Platform.runLater(() -> {
                    updateStatusForTest("测试失败: " + hostLine);
                    ControllerUtils.showErrorAlert("连接测试失败", e.getMessage());
                });
            }
            return null;
        });
    }

    /** 测试连接期间在底部状态栏给出即时反馈 */
    private void updateStatusForTest(String text) {
        // 连接列表底部有统计栏，直接更新最后一项标签即可
        if (lastUpdatedLabel != null) {
            lastUpdatedLabel.setText(text);
        }
    }



    private void handleConnectionByDisplayName(String displayName) {
        // 根据显示名称找到对应的连接参数
        List<ConnectionParams> allConnections = com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();
        
        for (ConnectionParams params : allConnections) {
            String name = params.getHost() + ":" + params.getPort() + " - " + params.getUsername();
            if (name.equals(displayName)) {
                // 通过参数管理器传递连接参数
                com.runzhi.aishell.common.util.ParameterManager.getInstance().put("connection_params", params);
                com.runzhi.aishell.common.util.ParameterManager.getInstance().put("switch_to_terminal", true);
                break;
            }
        }
    }

    private String formatLastUsedTime(LocalDateTime lastUsed) {
        if (lastUsed == null) {
            return "从未使用";
        }
        return lastUsed.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void updateStatistics() {
        if (connections != null) {
            int total = connections.size();
            long connected = connections.stream().filter(c -> "已连接".equals(c.getStatus())).count();
            long disconnected = total - connected;

            if (totalCountLabel != null) totalCountLabel.setText("总数：" + total);
            if (connectedCountLabel != null) connectedCountLabel.setText("已连接：" + connected);
            if (disconnectedCountLabel != null) disconnectedCountLabel.setText("未连接：" + disconnected);
            
            if (lastUpdatedLabel != null) {
                lastUpdatedLabel.setText("最后更新：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            }
        }
    }
    
    /**
     * 连接视图模型，用于在表格中展示连接信息
     */
    @Data
    public static class ConnectionViewModel {
        private SimpleStringProperty name;
        private SimpleStringProperty host;
        private SimpleStringProperty port;
        private SimpleStringProperty username;
        private SimpleStringProperty protocol;
        private SimpleStringProperty lastUsed;
        private SimpleStringProperty status;

        public ConnectionViewModel(String name, String host, String port, String username, 
                                 String protocol, String lastUsed, String status) {
            this.name = new SimpleStringProperty(name);
            this.host = new SimpleStringProperty(host);
            this.port = new SimpleStringProperty(port);
            this.username = new SimpleStringProperty(username);
            this.protocol = new SimpleStringProperty(protocol);
            this.lastUsed = new SimpleStringProperty(lastUsed);
            this.status = new SimpleStringProperty(status);
        }

        // Getter方法，用于TableColumn的CellValueFactory
        public SimpleStringProperty nameProperty() { return name; }
        public SimpleStringProperty hostProperty() { return host; }
        public SimpleStringProperty portProperty() { return port; }
        public SimpleStringProperty usernameProperty() { return username; }
        public SimpleStringProperty protocolProperty() { return protocol; }
        public SimpleStringProperty lastUsedProperty() { return lastUsed; }
        public SimpleStringProperty statusProperty() { return status; }
    }
}
