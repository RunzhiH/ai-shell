/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.util.ButtonConfigurator;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.service.TerminalConnectionService;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
@Slf4j
public class TerminalTabController {

    // 获取根节点
    @FXML
    private VBox terminalRoot;

    // 获取终端输出区域
    @FXML
    private InlineCssTextArea terminalOutput;

    // 获取模式选择框
    @FXML
    private ChoiceBox<String> inputModeChoiceBox;

    // 获取命令输入框
    @FXML
    private TextField commandInput;

    // 获取执行按钮
    @FXML
    private Button executeButton;
    
    // 获取重连按钮
    @FXML
    private Button reconnectButton;

    // 获取连接信息栏
    @FXML
    private HBox connectionInfoBar;

    // 获取连接状态标签
    @FXML
    private Label connectionStatusLabel;

    // 获取连接状态提示
    @FXML
    private Tooltip connectionStatusTooltip;

    // 获取连接主机标签
    @FXML
    private Label connectionHostLabel;

    // 获取连接主机提示
    @FXML
    private Tooltip hostTooltip;

    // 获取连接用户标签
    @FXML
    private Label connectionUserLabel;

    // 获取连接用户提示
    @FXML
    private Tooltip userTooltip;

    // 用于与TerminalPanelController通信的引用
    private TerminalPanelController terminalPanelController;
    private Tab parentTab;
    private String tabId;
    private SSHService sshService;

    // 设置 TerminalPanelController 引用
    public void setTerminalPanelController(TerminalPanelController controller, Tab tab, String tabId, SSHService sshService) {
        this.terminalPanelController = controller;
        this.parentTab = tab;
        this.tabId = tabId;
        this.sshService = sshService;
        
        // 初始化连接信息栏
        initializeConnectionInfoBar();
    }
    
    /**
     * 初始化连接信息栏
     */
    private void initializeConnectionInfoBar() {
        if (connectionInfoBar != null && sshService != null && tabId != null) {
            var connection = sshService.getTabConnection(tabId);
            if (connection != null) {
                updateConnectionInfoBar(connection);
            }
        }
    }
    
    /**
     * 更新连接信息栏显示（连接建立/重连成功后由连接服务调用）。
     */
    public void updateConnectionInfoBar(com.runzhi.aishell.ssh.SSHConnectionManager connection) {
        if (connectionInfoBar == null || connectionStatusLabel == null || 
            connectionHostLabel == null || connectionUserLabel == null) {
            return;
        }
        
        javafx.application.Platform.runLater(() -> {
            // 显示连接信息栏
            connectionInfoBar.setVisible(true);
            connectionInfoBar.setManaged(true);
            
            // 更新状态指示器
            boolean isConnected = connection.isConnected();
            connectionStatusLabel.setText(isConnected ? "●" : "○");
            connectionStatusLabel.setTextFill(isConnected ? 
                javafx.scene.paint.Color.web("#4ec9b0") : 
                javafx.scene.paint.Color.web("#f44336"));
            
            if (connectionStatusTooltip != null) {
                connectionStatusTooltip.setText(isConnected ? "已连接" : "已断开");
            }
            
            // 更新主机信息
            String hostInfo = connection.getHost() + ":" + connection.getPort();
            connectionHostLabel.setText(hostInfo);
            if (hostTooltip != null) {
                hostTooltip.setText("主机：" + hostInfo);
            }
            
            // 更新用户信息
            String userInfo = connection.getUsername();
            connectionUserLabel.setText(userInfo);
            if (userTooltip != null) {
                userTooltip.setText("用户：" + userInfo);
            }
            
            log.debug("Connection info bar updated: {}, User: {}, Connected: {}", hostInfo, userInfo, isConnected);
        });
    }
    
    /**
     * 隐藏连接信息栏
     */
    private void hideConnectionInfoBar() {
        if (connectionInfoBar != null) {
            javafx.application.Platform.runLater(() -> {
                connectionInfoBar.setVisible(false);
                connectionInfoBar.setManaged(false);
            });
        }
    }
    
    // 执行当前命令 - 供 FXML 调用
    @FXML
    private void executeCurrentCommand() {
        if (terminalPanelController != null && commandInput != null) {
            // 获取当前实际选中的标签页，而不是依赖可能过期的 parentTab 引用
            Tab currentTab = getCurrentTab();
            if (currentTab != null) {
                // 现在所有的终端都是通过连接参数创建的，直接调用 TerminalPanelController 中的方法来执行命令
                terminalPanelController.executeCurrentCommandInTab(currentTab, commandInput, inputModeChoiceBox);
            } else {
                // 如果无法获取当前标签页，使用原始的 parentTab 作为备选
                if (parentTab != null) {
                    terminalPanelController.executeCurrentCommandInTab(parentTab, commandInput, inputModeChoiceBox);
                }
            }
        }
    }
    
    // 重连到服务器
    @FXML
    private void reconnectToServer() {
        if (sshService != null && parentTab != null) {
            String tabId = parentTab.getId();
            // 如果标签页ID为null，尝试从参数中获取
            if (tabId == null && terminalPanelController != null) {
                tabId = terminalPanelController.getTabId(parentTab);
            }
            if (tabId != null) {
                var connection = sshService.getTabConnection(tabId);
                if (connection != null) {
                    // 尝试重新连接
                    boolean wasConnected = connection.isConnected();
                    
                    // 显示确认对话框
                    javafx.scene.control.Alert confirmAlert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
                    confirmAlert.setTitle("重新连接");
                    confirmAlert.setHeaderText("重新连接到服务器");
                    if (wasConnected) {
                        confirmAlert.setContentText("当前连接正常，是否重新建立连接?\n" + 
                            "主机: " + connection.getHost() + ":" + connection.getPort());
                    } else {
                        confirmAlert.setContentText("连接已断开，是否重新连接?\n" + 
                            "主机: " + connection.getHost() + ":" + connection.getPort());
                    }
                    
                    Optional<javafx.scene.control.ButtonType> result = confirmAlert.showAndWait();
                    if (result.isPresent() && result.get() == javafx.scene.control.ButtonType.OK) {
                        // 执行重新连接
                        terminalOutput.appendText("\n正在重新连接到 " + connection.getHost() + "...\n");
                        
                        // 使用TerminalService进行重新连接
                        // 如果连接存在，先断开再重新连接
                        if (wasConnected) {
                            connection.disconnect();
                        }
                        
                        // 重新建立连接
                        // 获取原始连接参数并重新连接
                        if (terminalPanelController != null) {
                            // 从参数管理器获取原始连接参数
                            com.runzhi.aishell.ssh.domain.ConnectionParams params = 
                                sshService.getTabConnectionParams(tabId);
                            if (params != null) {
                                // 通过 TerminalConnectionService 重新建立连接
                                TerminalConnectionService terminalConnectionService = 
                                    terminalPanelController.getTerminalConnectionService();
                                if (terminalConnectionService != null) {
                                    terminalConnectionService.establishSSHConnectionForTab(tabId, params, parentTab);
                                }
                                                            
                                terminalOutput.appendText("重新连接请求已发送...\n");
                            } else {
                                terminalOutput.appendText("无法获取连接参数，无法重新连接\n");
                            }
                        }
                    }
                } else {
                    terminalOutput.appendText("未找到当前连接，请先建立连接\n");
                }
            }
        } else {
            terminalOutput.appendText("SSH服务未初始化或标签页无效\n");
        }
    }
    
    // 获取当前实际选中的标签页
    private Tab getCurrentTab() {
        if (terminalPanelController != null && terminalPanelController.getTerminalTabPane() != null) {
            return terminalPanelController.getTerminalTabPane().getSelectionModel().getSelectedItem();
        }
        return null;
    }

    // 输入框回车事件
    @FXML
    private void onCommandInputAction() {
        executeCurrentCommand();
    }

    public void initializeDarkTheme() {
        if (terminalRoot != null) {
            // 设置深色主题背景
            terminalRoot.setStyle("-fx-background-color: #1e1e1e;");
        }
        
        if (terminalOutput != null) {
            terminalOutput.setEditable(false);
            terminalOutput.getStyleClass().addAll("terminal-output", "terminal-dark-output");
            
            // 不显示本地欢迎消息和提示符 - SSH 连接后服务器会发送自己的提示符
            
            // 添加右键菜单支持
            setupOutputContextMenu();
            
            // 添加双击支持执行命令
            setupDoubleClickExecution();
            
            // 添加键盘快捷键支持
            setupKeyboardShortcuts();
        }
        
        if (commandInput != null) {
            // 为命令输入框添加浅色主题样式类
            commandInput.getStyleClass().add("command-input");
        }
        
        if (executeButton != null) {
            // 为执行按钮添加浅色主题样式类
            executeButton.getStyleClass().add("command-button");
            
            // 为执行按钮添加点击事件处理
            executeButton.setOnAction(e -> executeCurrentCommand());
            
            // 配置按钮的额外属性
            ButtonConfigurator.getInstance().configureButton(executeButton, "执行", "执行当前输入的命令", "command-button");
        }
        
        if (reconnectButton != null) {
            // 为重连按钮添加浅色主题样式类
            reconnectButton.getStyleClass().add("command-button");
            
            // 为重连按钮添加点击事件处理
            reconnectButton.setOnAction(e -> reconnectToServer());
            
            // 配置按钮的额外属性
            ButtonConfigurator.getInstance().configureButton(reconnectButton, "重连", "重新连接到当前服务器", "command-button");
        }
        
        // 为输入框添加回车事件处理
        if (commandInput != null) {
            commandInput.setOnAction(e -> executeCurrentCommand());
        }
    }
    
    /**
     * 设置输出框右键菜单
     */
    private void setupOutputContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        
        // 添加"执行选中命令"菜单项
        MenuItem executeSelected = new MenuItem("执行选中命令");
        executeSelected.setOnAction(e -> {
            String selectedText = terminalOutput.getSelectedText();
            if (selectedText != null && !selectedText.trim().isEmpty()) {
                executeCommand(selectedText.trim());
            }
        });
        contextMenu.getItems().add(executeSelected);
        
        // 添加"复制选中内容"菜单项
        MenuItem copySelected = new MenuItem("复制选中内容");
        copySelected.setOnAction(e -> {
            String selectedText = terminalOutput.getSelectedText();
            if (selectedText != null && !selectedText.isEmpty()) {
                javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(selectedText);
                clipboard.setContent(content);
            }
        });
        contextMenu.getItems().add(copySelected);
        
        // 添加"执行当前行"菜单项
        MenuItem executeCurrentLine = new MenuItem("执行当前行");
        executeCurrentLine.setOnAction(e -> {
            String currentLine = getCurrentLineText();
            if (currentLine != null && !currentLine.trim().isEmpty()) {
                executeCommand(extractCommandFromLine(currentLine));
            }
        });
        contextMenu.getItems().add(executeCurrentLine);
        
        // 添加分隔符
        contextMenu.getItems().add(new SeparatorMenuItem());
        
        // 添加"清屏"菜单项
        MenuItem clearScreen = new MenuItem("清屏 (Clear Screen)");
        clearScreen.setOnAction(e -> {
            terminalOutput.clear();
            // 如果有SSH连接，发送clear命令
            if (sshService != null && parentTab != null) {
                String tabId = parentTab.getId();
                if (tabId != null) {
                    // 发送clear命令到SSH连接
                    var conn = sshService.getTabConnection(tabId);
                    if (conn != null) conn.sendCommand("clear\n");
                }
            }
        });
        contextMenu.getItems().add(clearScreen);
        
        // 添加"复制当前行"菜单项
        MenuItem copyCurrentLine = new MenuItem("复制当前行");
        copyCurrentLine.setOnAction(e -> {
            String currentLine = getCurrentLineText();
            if (currentLine != null && !currentLine.isEmpty()) {
                javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(currentLine);
                clipboard.setContent(content);
            }
        });
        contextMenu.getItems().add(copyCurrentLine);
        
        // 添加"查找"菜单项
        MenuItem findItem = new MenuItem("查找...");
        findItem.setOnAction(e -> {
            // 创建查找对话框
            javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
            dialog.setTitle("查找");
            dialog.setHeaderText("在终端输出中查找文本");

            javafx.scene.control.TextField findField = new javafx.scene.control.TextField();
            findField.setPromptText("输入要查找的文本");

            javafx.scene.control.ButtonType findButtonType = new javafx.scene.control.ButtonType("查找", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(findButtonType, javafx.scene.control.ButtonType.CANCEL);

            dialog.getDialogPane().setContent(findField);
            dialog.setResultConverter(dialogButton -> {
                if (dialogButton == findButtonType) {
                    return findField.getText();
                }
                return null;
            });

            Optional<String> result = dialog.showAndWait();
            result.ifPresent(searchText -> {
                // 在终端输出中查找文本
                String fullText = terminalOutput.getText();
                int startIndex = fullText.indexOf(searchText, terminalOutput.getCaretPosition());
                if (startIndex != -1) {
                    // 选中找到的文本
                    terminalOutput.selectRange(startIndex, startIndex + searchText.length());
                    terminalOutput.requestFocus();
                } else {
                    // 从头开始查找
                    startIndex = fullText.indexOf(searchText);
                    if (startIndex != -1) {
                        terminalOutput.selectRange(startIndex, startIndex + searchText.length());
                        terminalOutput.requestFocus();
                    } else {
                        // 没有找到，显示提示
                        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
                        alert.setTitle("查找");
                        alert.setHeaderText("未找到");
                        alert.setContentText("找不到 \"" + searchText + "\"");
                        alert.showAndWait();
                    }
                }
            });
        });
        contextMenu.getItems().add(findItem);
        
        terminalOutput.setContextMenu(contextMenu);
    }
    
    /**
     * 设置双击执行命令功能
     */
    private void setupDoubleClickExecution() {
        terminalOutput.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) { // 双击
                String currentLine = getCurrentLineText();
                if (currentLine != null && !currentLine.trim().isEmpty()) {
                    executeCommand(extractCommandFromLine(currentLine));
                }
            }
        });
    }
    
    /**
     * 设置键盘快捷键支持
     */
    private void setupKeyboardShortcuts() {
        if (terminalOutput != null) {
            terminalOutput.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    // 输出区是只读历史，Enter 应回到真正的命令输入光标。
                    if (commandInput != null) {
                        commandInput.requestFocus();
                        commandInput.positionCaret(commandInput.getLength());
                    }
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.C) {
                    // Ctrl+C：有选中文本则复制；无选中时向远端发送中断信号 ETX（此前只注释承诺未实现，2026-10-23 审计 P1-19）
                    String selectedText = terminalOutput.getSelectedText();
                    if (selectedText != null && !selectedText.isEmpty()) {
                        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                        content.putString(selectedText);
                        clipboard.setContent(content);
                    } else {
                        sendControlCharacter('\u0003');
                    }
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.A) {
                    // Ctrl+A - 全选
                    terminalOutput.selectAll();
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.L) {
                    // Ctrl+L - 清屏
                    terminalOutput.clear();
                    if (sshService != null && parentTab != null) {
                        String tabId = parentTab.getId();
                        if (tabId != null) {
                            var conn = sshService.getTabConnection(tabId);
                            if (conn != null) conn.sendCommand("clear\n");
                        }
                    }
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.R) {
                    // Ctrl+R - 打开命令历史对话框（此前输出"暂未完全实现"）
                    showCommandHistory();
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.F) {
                    // Ctrl+F - 打开查找对话框（此前是纯空操作，吞掉按键，2026-10-23 审计 P1-19）
                    showFindDialog();
                    event.consume();
                } else if (event.getCode() == KeyCode.F1) {
                    // F1 - 显示帮助
                    showHelp();
                    event.consume();
                } else if (event.getCode() == KeyCode.F2) {
                    // F2 - 显示当前连接信息
                    showConnectionInfo();
                    event.consume();
                } else if (event.getCode() == KeyCode.F3) {
                    // F3 - 重新连接
                    reconnectToServer();
                    event.consume();
                } else if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.C) {
                    // Ctrl+Shift+C - 复制
                    terminalOutput.copy();
                    event.consume();
                } else if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.V) {
                    // Ctrl+Shift+V - 粘贴
                    terminalOutput.paste();
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.U) {
                    // Ctrl+U - 清空当前行到行首
                    clearLineToBeginning();
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.K) {
                    // Ctrl+K - 清空当前行到行尾
                    clearLineToEnd();
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.W) {
                    // Ctrl+W - 删除光标前的单词
                    deleteWordBeforeCursor();
                    event.consume();
                } else if (event.isControlDown() && event.getCode() == KeyCode.D) {
                    // Ctrl+D - EOF信号
                    sendEOFSignal();
                    event.consume();
                }
            });
        }
    }
    
    /**
     * 清空当前行到行首
     */
    private void clearLineToBeginning() {
        if (terminalOutput != null) {
            int caretPos = terminalOutput.getCaretPosition();
            String text = terminalOutput.getText();
            
            if (text.isEmpty() || caretPos <= 0) return;
            
            int lineStart = text.lastIndexOf('\n', caretPos - 1);
            if (lineStart == -1) lineStart = 0;
            else lineStart++; // 跳过换行符
            final int finalLineStart = lineStart; // 使变量成为effectively final
            String beforeLine = text.substring(0, lineStart);
            final String finalBeforeLine = beforeLine; // 使变量成为effectively final
            String afterCaret = text.substring(caretPos);
            final String finalAfterCaret = afterCaret; // 使变量成为effectively final
            
            javafx.application.Platform.runLater(() -> {
                terminalOutput.replaceText(finalBeforeLine + finalAfterCaret);
                terminalOutput.moveTo(finalLineStart);
            });
        }
    }
    
    /**
     * 清空当前行到行尾
     */
    private void clearLineToEnd() {
        if (terminalOutput != null) {
            int caretPos = terminalOutput.getCaretPosition();
            String text = terminalOutput.getText();
            
            if (text.isEmpty() || caretPos >= text.length()) return;
            
            int lineEnd = text.indexOf('\n', caretPos);
            if (lineEnd == -1) lineEnd = text.length();
            final int finalLineEnd = lineEnd; // 使变量成为effectively final
            
            String beforeCaret = text.substring(0, caretPos);
            final String finalBeforeCaret = beforeCaret; // 使变量成为effectively final
            String afterLine = text.substring(lineEnd);
            final String finalAfterLine = afterLine; // 使变量成为effectively final
            
            javafx.application.Platform.runLater(() -> {
                terminalOutput.replaceText(finalBeforeCaret + finalAfterLine);
                terminalOutput.moveTo(caretPos);
            });
        }
    }
    
    /**
     * 删除光标前的单词
     */
    private void deleteWordBeforeCursor() {
        if (terminalOutput != null) {
            int caretPos = terminalOutput.getCaretPosition();
            String text = terminalOutput.getText();
            
            if (text.isEmpty() || caretPos <= 0) return;
            
            String beforeCaret = text.substring(0, caretPos);
            final String finalBeforeCaret = beforeCaret; // 使变量成为effectively final
            int wordStart = findWordStart(beforeCaret);
            final int finalWordStart = wordStart; // 使变量成为effectively final
            
            if (wordStart < caretPos) {
                String beforeWord = beforeCaret.substring(0, wordStart);
                final String finalBeforeWord = beforeWord; // 使变量成为effectively final
                String afterCaret = text.substring(caretPos);
                final String finalAfterCaret = afterCaret; // 使变量成为effectively final
                
                javafx.application.Platform.runLater(() -> {
                    terminalOutput.replaceText(finalBeforeWord + finalAfterCaret);
                    terminalOutput.moveTo(finalWordStart);
                });
            }
        }
    }
    
    /**
     * 查找单词开始位置
     */
    private int findWordStart(String text) {
        if (text.isEmpty()) return 0;
        
        int pos = text.length();
        // 跳过尾部空白字符
        while (pos > 0 && Character.isWhitespace(text.charAt(pos - 1))) {
            pos--;
        }
        
        // 找到单词的开始
        while (pos > 0 && !Character.isWhitespace(text.charAt(pos - 1))) {
            pos--;
        }
        
        return pos;
    }
    
    /**
     * 发送EOF信号（Ctrl+D）：向远端发送 EOT 字符，此前只打印假消息（2026-10-23 审计 P1-19）
     */
    private void sendEOFSignal() {
        sendControlCharacter('\u0004');
    }

    /**
     * 向当前标签的远端会话发送单个控制字符（ETX/EOT 等），优先使用真实交互终端。
     */
    private void sendControlCharacter(char controlChar) {
        try {
            if (parentTab != null && parentTab.getId() != null && sshService != null) {
                Object widget = parentTab.getProperties().get("interactiveTerminalWidget");
                if (widget instanceof com.techsenger.jeditermfx.ui.JediTermFxWidget term
                        && term.getTtyConnector() != null && term.getTtyConnector().isConnected()) {
                    term.getTtyConnector().write(String.valueOf(controlChar));
                    return;
                }
                var conn = sshService.getTabConnection(parentTab.getId());
                if (conn != null) conn.sendCommand(String.valueOf(controlChar));
            }
        } catch (Exception e) {
            log.warn("发送控制字符失败: {}", e.getMessage());
        }
    }
    
    /**
     * 显示帮助信息
     */
    private void showHelp() {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("终端帮助");
        alert.setHeaderText("终端快捷键和功能");
        alert.setContentText(
            "快捷键:\n" +
            "  Enter: 执行当前行命令\n" +
            "  Ctrl+Enter: 执行选中命令\n" +
            "  Ctrl+C: 中断当前进程\n" +
            "  Ctrl+L: 清屏\n" +
            "  Ctrl+A: 全选\n" +
            "  Ctrl+R: 显示命令历史\n" +
            "  Ctrl+Shift+C: 复制\n" +
            "  Ctrl+Shift+V: 粘贴\n" +
            "  F1: 显示帮助\n" +
            "  F2: 显示连接信息\n" +
            "  F3: 重新连接\n\n" +
            "功能:\n" +
            "  右键菜单: 执行命令、复制、清屏等\n" +
            "  双击: 执行当前行命令\n" +
            "  方向键: 导航命令历史\n" +
            "  Tab: 命令补全(部分支持)"
        );
        alert.showAndWait();
    }
    
    /**
     * 显示当前连接信息
     */
    private void showConnectionInfo() {
        if (sshService != null && parentTab != null) {
            String tabId = parentTab.getId();
            if (tabId != null) {
                var connection = sshService.getTabConnection(tabId);
                if (connection != null) {
                    String info = "连接信息:\n" +
                                 "  Tab ID: " + tabId + "\n" +
                                 "  连接状态: " + (connection.isConnected() ? "已连接" : "已断开") + "\n" +
                                 "  主机: " + connection.getHost() + "\n" +
                                 "  端口: " + connection.getPort() + "\n" +
                                 "  用户: " + connection.getUsername();
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
                    alert.setTitle("连接信息");
                    alert.setHeaderText("当前SSH连接详情");
                    alert.setContentText(info);
                    alert.showAndWait();
                }
            }
        }
    }
    
    /**
     * 重新连接
     */
    private void reconnect() {
        reconnectToServer();
    }
    
    /**
     * 显示命令历史：打开命令历史对话框（含搜索、复制与执行），替代此前"暂未完全实现"的桩（2026-10-23 审计 P1-19）
     */
    private void showCommandHistory() {
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getNavigationManager()
                    .openInNewWindow("command-history-dialog.fxml", "命令历史");
        } catch (Exception e) {
            log.warn("无法打开命令历史对话框: {}", e.getMessage());
        }
    }

    /**
     * 显示查找对话框：与右键菜单查找共用同一段查找逻辑（2026-10-23 审计 P1-19）
     */
    private void showFindDialog() {
        if (terminalOutput == null) return;
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("查找");
        dialog.setHeaderText("在终端输出中查找文本");
        javafx.scene.control.TextField findField = new javafx.scene.control.TextField();
        findField.setPromptText("输入要查找的文本");
        javafx.scene.control.ButtonType findButtonType =
                new javafx.scene.control.ButtonType("查找", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(findButtonType, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().setContent(findField);
        dialog.setResultConverter(dialogButton -> dialogButton == findButtonType ? findField.getText() : null);
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(searchText -> {
            if (searchText == null || searchText.isEmpty()) return;
            String fullText = terminalOutput.getText();
            int startIndex = fullText.indexOf(searchText, terminalOutput.getCaretPosition());
            if (startIndex == -1) startIndex = fullText.indexOf(searchText);
            if (startIndex != -1) {
                terminalOutput.selectRange(startIndex, startIndex + searchText.length());
                terminalOutput.requestFocus();
            } else {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.INFORMATION);
                alert.setTitle("查找");
                alert.setHeaderText(null);
                alert.setContentText("未找到: " + searchText);
                alert.showAndWait();
            }
        });
    }
    
    /**
     * 获取当前光标所在行的文本
     */
    private String getCurrentLineText() {
        int caretPos = terminalOutput.getCaretPosition();
        String fullText = terminalOutput.getText();
        if (fullText.isEmpty()) return "";
        
        String[] lines = fullText.split("\n", -1);
        int currentLineIndex = 0;
        int currentPos = 0;
        
        for (String line : lines) {
            if (caretPos <= currentPos + line.length()) {
                return line;
            }
            currentPos += line.length() + 1; // +1 for newline character
            currentLineIndex++;
        }
        return "";
    }
    
    /**
     * 从一行文本中提取命令部分（去掉提示符等）
     */
    private String extractCommandFromLine(String line) {
        // 仅去掉行首/行尾单个常见提示符字符；此前的 ^.*?[#$>%~:\-]\s* 会把
        // "ls -la" 截断成 "la"、"hello-world" 截断成 "world"（2026-10-23 审计 P1-19）
        if (line == null) return "";
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && "#$>%~:".indexOf(trimmed.charAt(0)) >= 0) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.length() > 1 && "#$>%~:".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
    /**
     * 执行命令
     */
    private void executeCommand(String command) {
        if (command != null && !command.isEmpty() && terminalPanelController != null) {
            Tab currentTab = getCurrentTab();
            if (currentTab != null) {
                // 将命令设置到输入框并执行
                if (commandInput != null) {
                    commandInput.setText(command);
                    terminalPanelController.executeCurrentCommandInTab(currentTab, commandInput, inputModeChoiceBox);
                }
            }
        }
    }
}
