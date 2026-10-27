/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.manager;

import com.runzhi.aishell.common.i18n.I18n;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 导航管理器
 * 
 * <p>负责页面导航、加载和缓存功能。
 * 从 AIShellModuleManager 中提取的独立管理器。
 */
@Slf4j
public class NavigationManager {

    private final Map<String, Object> controllers = new HashMap<>();
    private final Map<String, Parent> views = new HashMap<>();
    private final Map<String, Stage> auxiliaryStages = new HashMap<>();
    
    @Getter
    private Stage primaryStage;

    /**
     * 设置主舞台
     *
     * @param primaryStage 主舞台
     */
    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    /**
     * 加载页面
     *
     * @param fxmlPath FXML 文件路径
     * @return 页面根节点
     * @throws IOException 如果加载失败
     */
    public Parent loadPage(String fxmlPath) throws IOException {
        return loadPage(fxmlPath, true);
    }
    
    /**
     * 加载页面
     *
     * @param fxmlPath FXML 文件路径
     * @param useCache 是否使用缓存
     * @return 页面根节点
     * @throws IOException 如果加载失败
     */
    private Parent loadPage(String fxmlPath, boolean useCache) throws IOException {
        if (useCache && views.containsKey(fxmlPath)) {
            Parent cachedView = views.get(fxmlPath);
            // 检查视图是否已经在 scene-graph 中
            if (cachedView.getScene() != null) {
                // 视图已在 scene-graph 中，需要重新加载
                log.debug("Cached view is already in scene-graph, reloading: {}", fxmlPath);
                return loadNewPageInstance(fxmlPath);
            }
            return cachedView;
        }

        return loadNewPageInstance(fxmlPath);
    }
    
    /**
     * 加载新的页面实例 (不检查缓存)
     */
    private Parent loadNewPageInstance(String fxmlPath) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/" + fxmlPath));
        loader.setResources(I18n.bundle());
        Parent root = loader.load();
        Object controller = loader.getController();

        views.put(fxmlPath, root);
        controllers.put(fxmlPath, controller);

        log.debug("Loaded new page instance: {}", fxmlPath);
        return root;
    }

    /**
     * 切换到指定页面
     *
     * @param fxmlPath FXML 文件路径
     */
    public void switchToPage(String fxmlPath) {
        Platform.runLater(() -> {
            try {
                // 页面切换时每次都加载新实例，避免 scene-graph 冲突
                Parent root = loadPage(fxmlPath, false);
                Scene currentScene = primaryStage.getScene();
                if (currentScene != null) {
                    currentScene.setRoot(root);
                } else {
                    Scene newScene = new Scene(root, primaryStage.getWidth(), primaryStage.getHeight());
                    primaryStage.setScene(newScene);
                }
                log.debug("Switched to page: {}", fxmlPath);
            } catch (IOException e) {
                log.error("Failed to switch to page: {}", fxmlPath, e);
            }
        });
    }

    /**
     * 在新窗口中打开页面
     *
     * @param fxmlPath FXML 文件路径
     * @param title    窗口标题
     */
    public void openInNewWindow(String fxmlPath, String title) {
        openInNewWindow(fxmlPath, title, controller -> {});
    }

    /**
     * 在新窗口中打开页面，并在视图加载完成后回调（同一 FX 事件循环内同步执行，
     * 因此回调可以安全地拿到刚注册的控制器实例）。
     *
     * @param fxmlPath    FXML 文件路径
     * @param title       窗口标题
     * @param onLoaded    视图与控制器就绪后的回调
     */
    public void openInNewWindow(String fxmlPath, String title, java.util.function.Consumer<Object> onLoaded) {
        Platform.runLater(() -> {
            try {
                Stage existingStage = auxiliaryStages.get(fxmlPath);
                if (existingStage != null && existingStage.isShowing()) {
                    if (existingStage.isIconified()) existingStage.setIconified(false);
                    existingStage.toFront();
                    existingStage.requestFocus();
                    try {
                        onLoaded.accept(controllers.get(fxmlPath));
                    } catch (Exception ex) {
                        log.error("Post-load callback failed for existing {} window: {}",
                                fxmlPath, ex.getMessage(), ex);
                    }
                    return;
                }
                auxiliaryStages.remove(fxmlPath);

                // 新窗口需要加载新的视图实例，避免与已有 Scene 共享根节点
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/" + fxmlPath));
                loader.setResources(I18n.bundle());
        loader.setResources(I18n.bundle());
                loader.setResources(I18n.bundle());
                Parent root = loader.load();
                Object controller = loader.getController();

                // 只缓存控制器，不缓存视图（因为视图会被多个窗口使用）
                controllers.put(fxmlPath, controller);

                Stage newStage = new Stage();
                newStage.setTitle(title);
                Scene scene = new Scene(root, 800, 600);
                // 新窗口的 Scene 需注入全局样式表，否则面板样式缺失；
                // 不能依赖 FXML 内联 stylesheets，重复加载同一样式表会触发
                // CssStyleHelper.resolveLookups 无限递归（StackOverflowError）
                var themeManager = com.runzhi.aishell.common.util.ThemeManager.getInstance();
                for (String stylesheet : themeManager.getStylesheetsFor(themeManager.getCurrentTheme())) {
                    if (stylesheet != null) {
                        scene.getStylesheets().add(stylesheet);
                    }
                }
                newStage.setScene(scene);
                auxiliaryStages.put(fxmlPath, newStage);
                newStage.setOnHidden(event -> {
                    auxiliaryStages.remove(fxmlPath, newStage);
                    controllers.remove(fxmlPath, controller);
                });
                // 窗口关闭时取消控制器持有的后台任务（如正在进行的 AI 流式请求），
                // 避免请求在后台继续消耗 API 配额、回调继续写入已分离的 UI。
                if (controller instanceof com.runzhi.aishell.common.util.Cleanable cleanable) {
                    newStage.setOnCloseRequest(e -> {
                        try {
                            cleanable.onWindowClosing();
                        } catch (Exception ex) {
                            log.error("Failed to clean up controller on window close: {}", fxmlPath, ex);
                        }
                    });
                }
                newStage.show();
                log.debug("Opened page in new window: {}", fxmlPath);
                try {
                    onLoaded.accept(controller);
                } catch (Exception ex) {
                    log.error("Post-load callback failed for {}: {}", fxmlPath, ex.getMessage(), ex);
                }
            } catch (IOException e) {
                log.error("Failed to open page in new window: {}", fxmlPath, e);
            }
        });
    }

    /**
     * 获取控制器
     *
     * @param fxmlPath FXML 文件路径
     * @return 控制器实例
     */
    public Object getController(String fxmlPath) {
        return controllers.get(fxmlPath);
    }

    /**
     * 更新内容面板
     *
     * @param fxmlPath   FXML 文件路径
     * @param contentPane 内容面板
     */
    public void updateContentPane(String fxmlPath, StackPane contentPane) {
        Platform.runLater(() -> {
            try {
                Parent root = loadPage(fxmlPath);

                // 清空当前内容
                contentPane.getChildren().clear();

                // 添加新内容
                contentPane.getChildren().add(root);

                // 设置内容自适应
                if (root instanceof Region) {
                    Region region = (Region) root;
                    region.prefWidthProperty().bind(contentPane.widthProperty());
                    region.prefHeightProperty().bind(contentPane.heightProperty());
                }
                log.debug("Updated content pane with: {}", fxmlPath);
            } catch (IOException e) {
                log.error("Failed to update content pane: {}", fxmlPath, e);
            }
        });
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        views.clear();
        controllers.clear();
        log.debug("Navigation cache cleared");
    }

    /**
     * 检查页面是否已缓存
     *
     * @param fxmlPath FXML 文件路径
     * @return 如果已缓存返回 true
     */
    public boolean isPageCached(String fxmlPath) {
        return views.containsKey(fxmlPath);
    }

    /**
     * 获取缓存的页面数量
     *
     * @return 缓存的页面数量
     */
    public int getCacheSize() {
        return views.size();
    }
}
