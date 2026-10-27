/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal;

import com.jcraft.jsch.Session;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.core.model.StyleState;
import com.runzhi.aishell.terminal.enhancement.modules.EnhancedThemeModule.TerminalTheme;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Tab;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Pane;
import java.util.function.Consumer;

/** Mounts a real xterm-compatible terminal emulator into an existing terminal tab. */
public final class InteractiveTerminalView {
    private InteractiveTerminalView() {
    }

    public static JediTermFxWidget mount(Tab tab, Session session, String title) throws Exception {
        return mount(tab, session, title, null);
    }

    public static JediTermFxWidget mount(Tab tab, Session session, String title,
                                         Consumer<String> directoryListener) throws Exception {
        // 正常连接：替换标签里的遗留文本输出区；自动重连后重挂：标签里已经是真实终端，
        // 需要先关闭绑定在旧会话上的 widget，再用新会话重新挂接。
        Node oldOutput = findById(tab.getContent(), "terminalOutput");
        Pane parent;
        int index;
        if (oldOutput != null && oldOutput.getParent() instanceof Pane legacyParent) {
            parent = legacyParent;
            index = parent.getChildren().indexOf(oldOutput);
        } else {
            JediTermFxWidget existing = widget(tab);
            if (existing == null || !(existing.getPane().getParent() instanceof Pane paneParent)) {
                throw new IllegalStateException("Terminal output container was not found");
            }
            close(tab); // 断开绑定在旧会话上的通道
            parent = paneParent;
            index = parent.getChildren().indexOf(existing.getPane());
        }

        JschTtyConnector connector = new JschTtyConnector(session, title, directoryListener);
        DarkTerminalSettings settings = new DarkTerminalSettings();
        JediTermFxWidget widget = new ThemedTerminalWidget(120, 36, settings);
        widget.setTtyConnector(connector);
        Pane terminalPane = widget.getPane();
        terminalPane.setMinSize(0, 0);
        terminalPane.setPrefSize(800, 480);
        terminalPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        terminalPane.setStyle("-fx-background-color: #0d1117;");

        parent.getChildren().set(index, terminalPane);
        if (parent instanceof javafx.scene.layout.VBox) {
            javafx.scene.layout.VBox.setVgrow(terminalPane, javafx.scene.layout.Priority.ALWAYS);
        }

        tab.getProperties().put("interactiveTerminalWidget", widget);
        widget.start();
        widget.getPreferredFocusableNode().requestFocus();
        return widget;
    }

    /** Updates the file path field embedded in a terminal tab. */
    public static void updateFilePath(Tab tab, String directory) {
        if (tab == null || directory == null || directory.isBlank()) return;
        Runnable update = () -> {
            // 优先找文件管理器远程路径框；终端内若无则忽略
            Node path = findById(tab.getContent(), "pathField");
            if (path == null) path = findById(tab.getContent(), "currentPathField");
            if (path instanceof javafx.scene.control.TextField field) {
                String next = directory;
                String cur = field.getText();
                // 路径未变化时不 setText，避免无意义刷新观感
                if (cur == null || !cur.equals(next)) {
                    field.setText(next);
                    // 通知文件面板走受控同步（防抖 + 可见性）
                    Object data = tab.getContent() != null ? findUserDataController(tab.getContent()) : null;
                    if (data instanceof com.runzhi.aishell.controller.FileManagerPanelController fm) {
                        fm.applyTerminalDirectory(next);
                    }
                }
            }
        };
        if (javafx.application.Platform.isFxApplicationThread()) update.run();
        else javafx.application.Platform.runLater(update);
    }

    private static Object findUserDataController(Node node) {
        if (node == null) return null;
        Object data = node.getUserData();
        if (data instanceof com.runzhi.aishell.controller.FileManagerPanelController) return data;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Object hit = findUserDataController(child);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static final class DarkTerminalSettings extends DefaultSettingsProvider {
        private static final TerminalColor FOREGROUND = TerminalColor.rgb(220, 220, 220);
        private static final TerminalColor BACKGROUND = TerminalColor.rgb(13, 17, 23);

        @Override
        public TextStyle getDefaultStyle() {
            return new TextStyle(FOREGROUND, BACKGROUND);
        }

        @Override
        public TerminalColor getDefaultForeground() {
            return FOREGROUND;
        }

        @Override
        public TerminalColor getDefaultBackground() {
            return BACKGROUND;
        }

        /** 应用「设置 → 通用 → 字体大小」偏好；新挂接的终端立即生效。 */
        @Override
        public float getTerminalFontSize() {
            return java.util.prefs.Preferences.userNodeForPackage(
                            com.runzhi.aishell.controller.SettingsGeneralController.class)
                    .getInt("general.fontSize", 14);
        }
    }

    private static final class ThemedTerminalWidget extends JediTermFxWidget {
        private final DarkTerminalSettings themedSettings;
        private StyleState styleState;

        private ThemedTerminalWidget(int columns, int rows, DarkTerminalSettings settings) {
            super(columns, rows, settings);
            this.themedSettings = settings;
        }

        @Override
        protected StyleState createDefaultStyle() {
            styleState = super.createDefaultStyle();
            return styleState;
        }

        private void applyColors(TerminalColor foreground, TerminalColor background) {
            TextStyle style = new TextStyle(foreground, background);
            if (styleState != null) {
                styleState.setDefaultStyle(style);
                styleState.setCurrent(style);
            }
            getTerminal().characterAttributes(style);
            getPane().setStyle("-fx-background-color: " + toCss(background) + ";");
            getTerminalPanel().repaint();
        }
    }

    /** Applies a selected theme to the native terminal in the specified tab. */
    public static boolean applyTheme(Tab tab, TerminalTheme theme) {
        JediTermFxWidget widget = widget(tab);
        if (!(widget instanceof ThemedTerminalWidget themed) || theme == null) return false;
        int[][] colors = switch (theme) {
            case LIGHT_TERMINAL -> new int[][]{{0, 0, 0}, {255, 255, 255}};
            case MONOKAI -> new int[][]{{248, 248, 242}, {39, 40, 34}};
            case SOLARIZED_DARK -> new int[][]{{131, 148, 150}, {0, 43, 54}};
            case SOLARIZED_LIGHT -> new int[][]{{101, 123, 131}, {253, 246, 227}};
            case GRUVBOX -> new int[][]{{235, 219, 178}, {40, 40, 40}};
            case DRACULA -> new int[][]{{248, 248, 242}, {40, 42, 54}};
            case ONE_DARK -> new int[][]{{171, 178, 191}, {40, 44, 52}};
            case MATERIAL -> new int[][]{{238, 255, 255}, {38, 50, 56}};
            case CUSTOM, DARK_TERMINAL -> new int[][]{{220, 220, 220}, {13, 17, 23}};
        };
        themed.applyColors(TerminalColor.rgb(colors[0][0], colors[0][1], colors[0][2]),
                TerminalColor.rgb(colors[1][0], colors[1][1], colors[1][2]));
        return true;
    }

    private static String toCss(TerminalColor color) {
        com.techsenger.jeditermfx.core.Color rgb = color.toColor();
        return String.format("#%02x%02x%02x", rgb.getRed(), rgb.getGreen(), rgb.getBlue());
    }

    public static void close(Tab tab) {
        Object value = tab.getProperties().remove("interactiveTerminalWidget");
        if (value instanceof JediTermFxWidget widget) {
            widget.close();
        }
    }

    /** Clears the emulator scrollback and visible screen for an interactive tab. */
    public static boolean clear(Tab tab) {
        JediTermFxWidget widget = widget(tab);
        if (widget == null) return false;
        widget.getTerminalPanel().clearBuffer();
        widget.getTerminal().clearScreen();
        widget.getPreferredFocusableNode().requestFocus();
        return true;
    }

    /** Copies the current emulator selection to the system clipboard. */
    public static boolean copySelection(Tab tab) {
        JediTermFxWidget widget = widget(tab);
        if (widget == null) return false;
        String selected = widget.getTerminalPanel().selectedTextProperty().get();
        if (selected != null && !selected.isEmpty()) {
            widget.getTerminalPanel().handleCopy(false, false);
        }
        widget.getPreferredFocusableNode().requestFocus();
        return true;
    }

    /** Pastes clipboard text through the emulator so bracketed paste is respected. */
    public static boolean paste(Tab tab) {
        JediTermFxWidget widget = widget(tab);
        if (widget == null) return false;
        widget.getTerminalPanel().handlePaste();
        widget.getPreferredFocusableNode().requestFocus();
        return true;
    }

    private static JediTermFxWidget widget(Tab tab) {
        if (tab == null) return null;
        Object value = tab.getProperties().get("interactiveTerminalWidget");
        return value instanceof JediTermFxWidget widget ? widget : null;
    }

    /** Writes application feedback to the terminal buffer without sending it as a shell command. */
    public static void writeFeedback(Tab tab, String message) {
        if (tab == null || message == null || message.isBlank()) return;
        Object value = tab.getProperties().get("interactiveTerminalWidget");
        if (!(value instanceof JediTermFxWidget widget)) return;

        Runnable writer = () -> {
            var terminal = widget.getTerminal();
            terminal.carriageReturn();
            terminal.newLine();
            for (String line : message.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                terminal.characterAttributes(aiFeedbackStyle(line));
                terminal.writeCharacters(line);
                terminal.carriageReturn();
                terminal.newLine();
            }
            // 恢复普通终端样式，避免 AI 颜色污染后续 SSH 输出。
            terminal.characterAttributes(new TextStyle(
                    DarkTerminalSettings.FOREGROUND, DarkTerminalSettings.BACKGROUND));
            // Never write application feedback or redraw characters into the remote PTY.
        };
        if (javafx.application.Platform.isFxApplicationThread()) writer.run();
        else javafx.application.Platform.runLater(writer);
    }

    /** Mirrors an approved Agent command into the terminal buffer without executing it a second time. */
    public static void writeExecutedAgentCommand(Tab tab, String command) {
        if (tab == null || command == null || command.isBlank()) return;
        Object value = tab.getProperties().get("interactiveTerminalWidget");
        if (!(value instanceof JediTermFxWidget widget)) return;
        Runnable writer = () -> {
            var terminal = widget.getTerminal();
            terminal.carriageReturn();
            terminal.newLine();
            terminal.characterAttributes(new TextStyle(
                    TerminalColor.rgb(126, 231, 135), DarkTerminalSettings.BACKGROUND));
            terminal.writeCharacters("[Agent 执行] " + command.replace("\r", "").replace("\n", " "));
            terminal.carriageReturn();
            terminal.newLine();
            terminal.characterAttributes(new TextStyle(
                    DarkTerminalSettings.FOREGROUND, DarkTerminalSettings.BACKGROUND));
            widget.getTerminalPanel().repaint();
        };
        if (javafx.application.Platform.isFxApplicationThread()) writer.run();
        else javafx.application.Platform.runLater(writer);
    }

    /** Mirrors output captured from the Agent's separate SSH exec channel into the visible terminal. */
    public static void writeAgentExecutionOutput(Tab tab, String output) {
        if (tab == null || output == null || output.isBlank()) return;
        Object value = tab.getProperties().get("interactiveTerminalWidget");
        if (!(value instanceof JediTermFxWidget widget)) return;
        Runnable writer = () -> {
            var terminal = widget.getTerminal();
            terminal.characterAttributes(new TextStyle(
                    TerminalColor.rgb(139, 148, 158), DarkTerminalSettings.BACKGROUND));
            String normalized = output.replace("\r\n", "\n").replace('\r', '\n');
            for (String line : normalized.split("\n", -1)) {
                terminal.writeCharacters(line);
                terminal.carriageReturn();
                terminal.newLine();
            }
            terminal.characterAttributes(new TextStyle(
                    DarkTerminalSettings.FOREGROUND, DarkTerminalSettings.BACKGROUND));
            widget.getTerminalPanel().repaint();
        };
        if (javafx.application.Platform.isFxApplicationThread()) writer.run();
        else javafx.application.Platform.runLater(writer);
    }

    /** Shows a non-blocking command confirmation panel inside the current terminal tab. */
    public static void showCommandConfirmation(Tab tab, String command,
                                               Runnable onConfirm, Runnable onCancel) {
        showCommandConfirmation(tab, command, com.runzhi.aishell.agent.CommandRisk.LOW, onConfirm, onCancel);
    }

    public static void showCommandConfirmation(Tab tab, String command,
                                               com.runzhi.aishell.agent.CommandRisk risk,
                                               Runnable onConfirm, Runnable onCancel) {
        Node inputBar = findById(tab == null ? null : tab.getContent(), "commandInputHBox");
        if (inputBar == null || !(inputBar.getParent() instanceof VBox parent)) {
            onCancel.run();
            return;
        }

        Node previous = findById(tab.getContent(), "aiCommandConfirmation");
        if (previous != null && previous.getParent() instanceof Pane previousParent) {
            previousParent.getChildren().remove(previous);
        }

        Label title = new Label("AI 待执行命令 · " + risk.label() + "（确认后才会发送到服务器）");
        title.setStyle("-fx-text-fill: " + risk.color() + "; -fx-font-weight: bold;");
        TextArea preview = new TextArea(command);
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(Math.min(5, Math.max(1, command.split("\\R", -1).length)));
        preview.setStyle("-fx-control-inner-background: #161b22; -fx-text-fill: #7ee787; "
                + "-fx-font-family: 'Cascadia Mono', 'Consolas', monospace;");

        Button execute = new Button("执行命令");
        execute.setDefaultButton(true);
        execute.setStyle("-fx-background-color: " + risk.color() + "; -fx-text-fill: white; -fx-font-weight: bold;");
        Button cancel = new Button("取消");
        HBox actions = new HBox(8, execute, cancel);

        VBox confirmation = new VBox(6, title, preview, actions);
        confirmation.setId("aiCommandConfirmation");
        confirmation.setStyle("-fx-background-color: #21262d; -fx-padding: 10; "
                + "-fx-border-color: #d29922; -fx-border-width: 1 0 1 0;");

        Runnable remove = () -> parent.getChildren().remove(confirmation);
        execute.setOnAction(event -> {
            if (risk == com.runzhi.aishell.agent.CommandRisk.HIGH) {
                Alert second = new Alert(Alert.AlertType.CONFIRMATION);
                second.setTitle("高风险命令二次确认");
                second.setHeaderText("该命令可能造成不可恢复的系统或数据变更");
                second.setContentText(command);
                if (second.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
            }
            remove.run();
            onConfirm.run();
        });
        cancel.setOnAction(event -> {
            remove.run();
            onCancel.run();
        });

        int inputIndex = parent.getChildren().indexOf(inputBar);
        parent.getChildren().add(Math.max(0, inputIndex), confirmation);
        preview.requestFocus();
    }

    private static TextStyle aiFeedbackStyle(String line) {
        TerminalColor color;
        if (line.contains("错误") || line.contains("失败") || line.contains("未配置")) {
            color = TerminalColor.rgb(255, 107, 107);
        } else if (line.contains("正在") || line.contains("等待用户确认")) {
            color = TerminalColor.rgb(255, 203, 107);
        } else if (line.contains("生成命令") || line.contains("待执行命令")) {
            color = TerminalColor.rgb(126, 231, 135);
        } else {
            color = TerminalColor.rgb(102, 217, 239);
        }
        return new TextStyle(color, DarkTerminalSettings.BACKGROUND,
                java.util.EnumSet.of(TextStyle.Option.BOLD));
    }

    private static Node findById(Node node, String id) {
        if (node == null) return null;
        if (id.equals(node.getId())) return node;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findById(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }
}
