/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.ai.AIShellModuleManager;
import com.runzhi.aishell.common.util.DialogUtils;
import com.runzhi.aishell.features.bookmark.BookmarkManager;
import com.runzhi.aishell.features.bookmark.CommandBookmark;
import com.runzhi.aishell.features.bookmark.CommandTemplate;
import com.runzhi.aishell.features.bookmark.CommandTemplateManager;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 书签和命令模板面板控制器
 */
@Slf4j
public class BookmarkPanelController extends BaseController implements Initializable {
    
    @FXML private TextField searchField;
    @FXML private Button addBookmarkButton;
    @FXML private Button addTemplateButton;
    @FXML private Button refreshButton;
    @FXML private ComboBox<String> categoryComboBox;
    @FXML private TableView<CommandBookmark> bookmarkTable;
    @FXML private TableColumn<CommandBookmark, String> bookmarkNameColumn;
    @FXML private TableColumn<CommandBookmark, String> bookmarkCategoryColumn;
    @FXML private TableColumn<CommandBookmark, String> bookmarkUsageColumn;
    @FXML private TitledPane bookmarkDetailPane;
    @FXML private TextField detailNameField;
    @FXML private TextArea detailCommandArea;
    @FXML private TextField detailDescriptionField;
    @FXML private Label detailCategoryLabel;
    
    @FXML private ComboBox<String> templateCategoryComboBox;
    @FXML private ListView<CommandTemplate> templateList;
    @FXML private TitledPane templateDetailPane;
    @FXML private TextField templateNameField;
    @FXML private VBox templateParametersBox;
    @FXML private TextArea commandPreviewArea;
    @FXML private Button executeTemplateButton;
    @FXML private Button copyTemplateButton;
    @FXML private Button editTemplateButton;
    
    @FXML private Label statusLabel;
    @FXML private Label bookmarkCountLabel;
    @FXML private Label templateCountLabel;
    
    private BookmarkManager bookmarkManager;
    private CommandTemplateManager templateManager;
    private AIShellModuleManager moduleManager;
    private CommandBookmark selectedBookmark;
    private CommandTemplate selectedTemplate;
    private Map<String, Control> parameterControls;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        super.initialize(location, resources);
        bookmarkManager = BookmarkManager.getInstance();
        templateManager = CommandTemplateManager.getInstance();
        moduleManager = AIShellModuleManager.getInstance();
        parameterControls = new HashMap<>();
        initializeBookmarkTable();
        initializeTemplateList();
        loadBookmarks();
        loadTemplates();
        bindListeners();
        log.info("书签面板初始化完成");
    }
    
    private void initializeBookmarkTable() {
        bookmarkNameColumn.setCellValueFactory(cellData -> 
            new javafx.beans.property.SimpleStringProperty(cellData.getValue().getName()));
        bookmarkCategoryColumn.setCellValueFactory(cellData -> 
            new javafx.beans.property.SimpleStringProperty(cellData.getValue().getCategory()));
        bookmarkUsageColumn.setCellValueFactory(cellData -> {
            String usageCount = String.valueOf(cellData.getValue().getUsageCount());
            return new javafx.beans.property.SimpleStringProperty(usageCount);
        });
        bookmarkTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            selectedBookmark = newVal;
            if (newVal != null) showBookmarkDetails(newVal);
            else clearBookmarkDetails();
        });
    }
    
    private void initializeTemplateList() {
        templateList.setCellFactory(param -> new ListCell<CommandTemplate>() {
            @Override
            protected void updateItem(CommandTemplate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        templateList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            selectedTemplate = newVal;
            if (newVal != null) showTemplateDetails(newVal);
            else clearTemplateDetails();
        });
    }
    
    private void loadBookmarks() {
        Platform.runLater(() -> {
            List<CommandBookmark> bookmarks = bookmarkManager.getAllBookmarks();
            bookmarkTable.getItems().setAll(bookmarks);
            categoryComboBox.getItems().clear();
            categoryComboBox.getItems().add("所有分类");
            categoryComboBox.getItems().addAll(bookmarkManager.getCategories());
            bookmarkCountLabel.setText(String.valueOf(bookmarks.size()));
            statusLabel.setText("已加载 " + bookmarks.size() + " 个书签");
        });
    }
    
    private void loadTemplates() {
        Platform.runLater(() -> {
            List<CommandTemplate> templates = templateManager.getAllTemplates();
            templateList.getItems().setAll(templates);
            Set<String> categories = templates.stream().map(CommandTemplate::getCategory).filter(Objects::nonNull).collect(Collectors.toSet());
            templateCategoryComboBox.getItems().clear();
            templateCategoryComboBox.getItems().add("所有分类");
            templateCategoryComboBox.getItems().addAll(categories);
            templateCountLabel.setText(String.valueOf(templates.size()));
            statusLabel.setText("已加载 " + templates.size() + " 个模板");
        });
    }
    
    private void bindListeners() {
        categoryComboBox.valueProperty().addListener((obs, oldVal, newVal) -> filterBookmarksByCategory(newVal));
        templateCategoryComboBox.valueProperty().addListener((obs, oldVal, newVal) -> filterTemplatesByCategory(newVal));
        searchField.textProperty().addListener((obs, oldVal, newVal) -> searchBookmarks(newVal));
        bookmarkManager.addListener(() -> Platform.runLater(this::loadBookmarks));
    }
    
    private void showBookmarkDetails(CommandBookmark bookmark) {
        detailNameField.setText(bookmark.getName());
        detailCommandArea.setText(bookmark.getCommand());
        detailDescriptionField.setText(bookmark.getDescription());
        detailCategoryLabel.setText(bookmark.getCategory());
        bookmarkDetailPane.setExpanded(true);
    }
    
    private void clearBookmarkDetails() {
        detailNameField.clear();
        detailCommandArea.clear();
        detailDescriptionField.clear();
        detailCategoryLabel.setText("");
        bookmarkDetailPane.setExpanded(false);
    }
    
    private void showTemplateDetails(CommandTemplate template) {
        templateNameField.setText(template.getName());
        commandPreviewArea.setText(template.getTemplate());
        generateParameterInputs(template);
        templateDetailPane.setExpanded(true);
    }
    
    private void clearTemplateDetails() {
        templateNameField.clear();
        commandPreviewArea.clear();
        templateParametersBox.getChildren().clear();
        parameterControls.clear();
        templateDetailPane.setExpanded(false);
    }
    
    private void generateParameterInputs(CommandTemplate template) {
        templateParametersBox.getChildren().clear();
        parameterControls.clear();
        for (CommandTemplate.TemplateParameter param : template.getParameters()) {
            VBox paramBox = new VBox(5);
            Label label = new Label(param.getName() + (param.isRequired() ? " *" : ""));
            label.setStyle("-fx-font-weight: bold;");
            Control control;
            if ("choice".equals(param.getType()) && !param.getChoices().isEmpty()) {
                ComboBox<String> comboBox = new ComboBox<>();
                comboBox.getItems().addAll(param.getChoices());
                if (param.getDefaultValue() != null) comboBox.setValue(param.getDefaultValue());
                control = comboBox;
            } else {
                TextField textField = new TextField(param.getDefaultValue() != null ? param.getDefaultValue() : "");
                textField.setPromptText(param.getDescription());
                control = textField;
            }
            if (control instanceof TextField) {
                ((TextField) control).textProperty().addListener((obs, oldVal, newVal) -> updateCommandPreview(template));
            } else if (control instanceof ComboBox) {
                ((ComboBox<?>) control).valueProperty().addListener((obs, oldVal, newVal) -> updateCommandPreview(template));
            }
            parameterControls.put(param.getName(), control);
            paramBox.getChildren().addAll(label, control);
            templateParametersBox.getChildren().add(paramBox);
        }
    }
    
    private void updateCommandPreview(CommandTemplate template) {
        Map<String, String> params = new HashMap<>();
        parameterControls.forEach((name, control) -> {
            if (control instanceof TextField) params.put(name, ((TextField) control).getText());
            else if (control instanceof ComboBox) params.put(name, ((ComboBox<?>) control).getValue() != null ? ((ComboBox<?>) control).getValue().toString() : "");
        });
        commandPreviewArea.setText(template.render(params));
    }
    
    private void filterBookmarksByCategory(String category) {
        if ("所有分类".equals(category) || category == null) loadBookmarks();
        else bookmarkTable.getItems().setAll(bookmarkManager.getBookmarksByCategory(category));
    }
    
    private void filterTemplatesByCategory(String category) {
        if ("所有分类".equals(category) || category == null) loadTemplates();
        else templateList.getItems().setAll(templateManager.getTemplatesByCategory(category));
    }
    
    private void searchBookmarks(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) loadBookmarks();
        else bookmarkTable.getItems().setAll(bookmarkManager.searchBookmarks(keyword));
    }
    
    @FXML private void handleAddBookmark() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/bookmark-edit-dialog.fxml"));
            loader.setResources(I18n.bundle());
            Parent root = loader.load();
            BookmarkEditDialogController controller = loader.getController();
            controller.setAddMode();
            controller.setOnSaveCallback(this::loadBookmarks);
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("添加书签");
            dialog.getDialogPane().setContent(root);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.setResultConverter(btn -> null);
            Platform.runLater(() -> {
                Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
                if (okButton != null) okButton.setVisible(false);
            });
            dialog.showAndWait();
        } catch (Exception e) {
            log.error("打开添加书签对话框失败：{}", e.getMessage(), e);
            DialogUtils.showErrorDialog("错误", "无法打开添加书签对话框", e.getMessage());
        }
    }
    
    /**
     * 添加命令模板：名称 / 分类 / 模板正文 / 描述，保存后刷新列表（2026-10-23 审计 UI-3，此前是"功能开发中"桩）
     */
    @FXML private void handleAddTemplate() {
        javafx.scene.control.Dialog<CommandTemplate> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("添加模板");
        dialog.setHeaderText("新建命令模板");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        TextField nameField = new TextField();
        nameField.setPromptText("模板名称");
        TextField categoryField = new TextField();
        categoryField.setPromptText("分类，例如：文件 / 网络");
        TextArea templateArea = new TextArea();
        templateArea.setPromptText("模板正文，可用 ${参数名} 占位符");
        templateArea.setPrefRowCount(5);
        TextArea descArea = new TextArea();
        descArea.setPromptText("描述（可选）");
        descArea.setPrefRowCount(2);

        grid.add(new Label("名称"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("分类"), 0, 1);
        grid.add(categoryField, 1, 1);
        grid.add(new Label("模板"), 0, 2);
        grid.add(templateArea, 1, 2);
        grid.add(new Label("描述"), 0, 3);
        grid.add(descArea, 1, 3);

        javafx.scene.control.ButtonType saveButtonType =
                new javafx.scene.control.ButtonType("保存", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);

        javafx.scene.control.Button saveButton = (javafx.scene.control.Button)
                dialog.getDialogPane().lookupButton(saveButtonType);
        if (saveButton != null) {
            saveButton.setDisable(true);
            nameField.textProperty().addListener((obs, o, n) ->
                    saveButton.setDisable(n == null || n.trim().isEmpty() || templateArea.getText().trim().isEmpty()));
            templateArea.textProperty().addListener((obs, o, n) ->
                    saveButton.setDisable(n == null || n.trim().isEmpty() || nameField.getText().trim().isEmpty()));
        }

        dialog.setResultConverter(button -> {
            if (button != saveButtonType) return null;
            CommandTemplate template = new CommandTemplate(
                    nameField.getText().trim(),
                    templateArea.getText().trim(),
                    categoryField.getText().trim().isEmpty() ? "未分类" : categoryField.getText().trim());
            template.setDescription(descArea.getText().trim());
            return template;
        });

        dialog.showAndWait().ifPresent(template -> {
            templateManager.addTemplate(template);
            statusLabel.setText("已添加模板：" + template.getName());
            loadTemplates();
        });
    }
    
    @FXML private void handleRefresh() {
        loadBookmarks();
        loadTemplates();
        statusLabel.setText("已刷新");
    }
    
    @FXML private void handleExecuteBookmark() {
        if (selectedBookmark == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择一个书签");
            alert.showAndWait();
            return;
        }
        bookmarkManager.executeBookmark(selectedBookmark.getId());
        com.runzhi.aishell.common.util.ParameterManager.getInstance().put("switch_to_terminal", true, 3000L);
        com.runzhi.aishell.common.util.ParameterManager.getInstance().put("execute_command", selectedBookmark.getCommand(), 3000L);
        statusLabel.setText("已执行：" + selectedBookmark.getName());
        loadBookmarks();
    }
    
    @FXML private void handleEditBookmark() {
        if (selectedBookmark == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择一个书签");
            alert.showAndWait();
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/bookmark-edit-dialog.fxml"));
            loader.setResources(I18n.bundle());
            Parent root = loader.load();
            BookmarkEditDialogController controller = loader.getController();
            controller.setEditMode(selectedBookmark);
            controller.setOnSaveCallback(this::loadBookmarks);
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("编辑书签");
            dialog.getDialogPane().setContent(root);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.setResultConverter(btn -> null);
            Platform.runLater(() -> {
                Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
                if (okButton != null) okButton.setVisible(false);
            });
            dialog.showAndWait();
        } catch (Exception e) {
            log.error("打开编辑书签对话框失败：{}", e.getMessage(), e);
            DialogUtils.showErrorDialog("错误", "无法打开编辑书签对话框", e.getMessage());
        }
    }
    
    @FXML private void handleDeleteBookmark() {
        if (selectedBookmark == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择一个书签");
            alert.showAndWait();
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认删除");
        alert.setHeaderText("确定要删除书签 \"" + selectedBookmark.getName() + "\" 吗？");
        alert.setContentText("此操作不可撤销。");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            bookmarkManager.removeBookmark(selectedBookmark.getId());
            selectedBookmark = null;
            clearBookmarkDetails();
            statusLabel.setText("已删除书签");
        }
    }
    
    @FXML private void handleCopyCommand() {
        if (selectedBookmark == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择一个书签");
            alert.showAndWait();
            return;
        }
        final javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(selectedBookmark.getCommand());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("已复制命令到剪贴板");
    }
    
    @FXML private void handleExecuteTemplate() {
        if (selectedTemplate == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择一个模板");
            alert.showAndWait();
            return;
        }
        Map<String, String> params = new HashMap<>();
        parameterControls.forEach((name, control) -> {
            if (control instanceof TextField) params.put(name, ((TextField) control).getText());
            else if (control instanceof ComboBox) params.put(name, ((ComboBox<?>) control).getValue() != null ? ((ComboBox<?>) control).getValue().toString() : "");
        });
        String command = selectedTemplate.render(params);
        com.runzhi.aishell.common.util.ParameterManager.getInstance().put("switch_to_terminal", true, 3000L);
        com.runzhi.aishell.common.util.ParameterManager.getInstance().put("execute_command", command, 3000L);
        statusLabel.setText("已执行模板命令");
    }
    
    @FXML private void handleCopyTemplate() {
        if (selectedTemplate == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择一个模板");
            alert.showAndWait();
            return;
        }
        final javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(commandPreviewArea.getText());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("已复制命令到剪贴板");
    }
    
    /**
     * 编辑选中的命令模板：预填现有字段，保存后刷新列表（2026-10-23 审计 UI-3，此前是"功能开发中"桩）
     */
    @FXML private void handleEditTemplate() {
        if (selectedTemplate == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择一个模板");
            alert.showAndWait();
            return;
        }

        javafx.scene.control.Dialog<CommandTemplate> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("编辑模板");
        dialog.setHeaderText("编辑命令模板：" + selectedTemplate.getName());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        TextField nameField = new TextField(selectedTemplate.getName());
        TextField categoryField = new TextField(selectedTemplate.getCategory());
        TextArea templateArea = new TextArea(selectedTemplate.getTemplate());
        templateArea.setPrefRowCount(5);
        TextArea descArea = new TextArea(selectedTemplate.getDescription());
        descArea.setPrefRowCount(2);

        grid.add(new Label("名称"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("分类"), 0, 1);
        grid.add(categoryField, 1, 1);
        grid.add(new Label("模板"), 0, 2);
        grid.add(templateArea, 1, 2);
        grid.add(new Label("描述"), 0, 3);
        grid.add(descArea, 1, 3);

        javafx.scene.control.ButtonType saveButtonType =
                new javafx.scene.control.ButtonType("保存", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != saveButtonType) return null;
            selectedTemplate.setName(nameField.getText().trim());
            selectedTemplate.setCategory(categoryField.getText().trim().isEmpty() ? "未分类" : categoryField.getText().trim());
            selectedTemplate.setTemplate(templateArea.getText().trim());
            selectedTemplate.setDescription(descArea.getText().trim());
            return selectedTemplate;
        });

        dialog.showAndWait().ifPresent(template -> {
            templateManager.updateTemplate(template);
            statusLabel.setText("已更新模板：" + template.getName());
            loadTemplates();
        });
    }
}