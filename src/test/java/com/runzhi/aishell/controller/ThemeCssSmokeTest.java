package com.runzhi.aishell.controller;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.css.PseudoClass;
import com.runzhi.aishell.common.util.ThemeManager;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主题 CSS 冒烟测试：确保深/浅色入口都能把 v6 token 解析到共享控件上。
 * <p>
 * 这里直接检查计算后的背景、边框和文字颜色，避免只“加载成功”但 token 已被旧样式覆盖。
 */
class ThemeCssSmokeTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @Test
    void darkPaletteResolvesOnSharedControls() throws Exception {
        assertPalette("style.css",
                Color.web("#202020"), Color.web("#323232"), Color.web("#426B94"),
                Color.web("#2D2D2D"), Color.web("#7A7A7A"), Color.web("#D4D4D4"),
                Color.web("#898989"));
    }

    @Test
    void lightPaletteResolvesOnSharedControls() throws Exception {
        assertPalette("light-theme.css",
                Color.web("#F0F0F0"), Color.web("#E8E8E8"), Color.web("#426B94"),
                Color.web("#FFFFFF"), Color.web("#858585"), Color.web("#292929"),
                Color.web("#727272"));
    }

    /** Validate state precedence and live theme switching on the same controls. */
    @Test
    void liveThemeSwitchPreservesSelectionFocusAndDisabledHierarchy() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Button agent = new Button("Agent");
                agent.getStyleClass().addAll("nav-item", "active");
                VBox toolbar = new VBox(agent);
                toolbar.getStyleClass().add("topbar");
                Button warning = new Button("批准执行");
                warning.getStyleClass().add("warning-button");
                Label aiTitle = new Label("AI 助手");
                aiTitle.getStyleClass().add("ai-title");
                Label subtitle = new Label("名称、IP 和传输状态");
                subtitle.getStyleClass().add("server-node-sub");
                Label title = new Label("命令");
                title.getStyleClass().add("command-palette-item-title");
                Label group = new Label("操作分组");
                group.getStyleClass().add("command-palette-item-group");
                // Isolate CSS state precedence from ListCell's item/skin lifecycle.
                VBox cell = new VBox(title, group);
                cell.getStyleClass().add("list-cell");
                cell.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), true);
                VBox palette = new VBox(cell);
                palette.getStyleClass().add("command-palette-list");
                TextField path = new TextField("/var/log");
                path.getStyleClass().add("command-palette-field");
                TextArea terminal = new TextArea("Terminal fallback output");
                terminal.getStyleClass().add("terminal-output");
                VBox root = new VBox(toolbar, warning, aiTitle, subtitle, palette, path, terminal);
                Scene scene = new Scene(root, 640, 480);
                for (ThemeManager.Theme theme : new ThemeManager.Theme[]{
                        ThemeManager.Theme.DARK, ThemeManager.Theme.LIGHT, ThemeManager.Theme.DARK}) {
                    ThemeManager.getInstance().applyTheme(scene, theme);
                    boolean dark = theme == ThemeManager.Theme.DARK;
                    warning.setDisable(false);
                    agent.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), true);
                    root.applyCss();
                    root.layout();
                    assertEquals(1, scene.getStylesheets().size(), "theme entry must not accumulate");
                    assertEquals(Color.web(dark ? "#35485B" : "#DCE7F1"), fill(agent));
                    assertEquals(Color.web(dark ? "#8DB4D8" : "#426B94"), topBorder(agent),
                            "selected toolbar action must keep its focus boundary");
                    assertEquals(Color.web(dark ? "#AAAAAA" : "#5F5F5F"), aiTitle.getTextFill(),
                            "AI titles use neutral text instead of a purple identity");
                    assertTrue(subtitle.getFont().getSize() >= 12, "auxiliary text must remain readable");
                    assertEquals(Color.web(dark ? "#FFFFFF" : "#315B80"), title.getTextFill());
                    assertEquals(Color.web(dark ? "#D4D4D4" : "#315B80"), group.getTextFill());
                    assertEquals(Color.web(dark ? "#7A7A7A" : "#858585"), topBorder(path));
                    assertEquals(Color.web(dark ? "#B89B60" : "#9A7938"), topBorder(warning));
                    javafx.scene.text.Text output = (javafx.scene.text.Text) terminal.lookup(".text");
                    assertNotNull(output, "fallback terminal skin must expose its rendered text");
                    assertEquals(Color.web("#D4D4D4"), output.getFill(),
                            "light UI must not turn fallback terminal text dark");
                    warning.setDisable(true);
                    root.applyCss();
                    assertTrue(isTransparent(fill(warning)), "disabled warning action must lose its surface");
                    assertTrue(isTransparent(topBorder(warning)), "disabled warning action must lose its outline");
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "theme state checks timed out");
        if (failure.get() != null) throw new AssertionError("theme state checks failed", failure.get());
    }

    /**
     * 回归：会话状态圆点的内联样式只能用主题里真实定义的颜色变量。
     * <p>此前用的是 -fx-text-muted-color，style.css / light-theme.css 都没有定义它，
     * JavaFX 每次应用都会打印 "Could not resolve '-fx-text-muted-color' ..."，
     * 且圆点保持默认颜色。</p>
     */
    @Test
    void statusDotInlineStylesResolveAgainstThemeTokens() throws Exception {
        fx(() -> {
            Label dot = new Label("●");
            VBox root = new VBox(dot);
            Scene scene = new Scene(root, 120, 60);
            scene.getStylesheets().add(getClass().getResource(
                    "/com/runzhi/aishell/style.css").toExternalForm());

            dot.setStyle(MainViewController.STATUS_DOT_IDLE_STYLE);
            root.applyCss();
            assertEquals(Color.web("#AAAAAA"), dot.getTextFill(),
                    "未连接状态点应解析为主题次级文字色");

            dot.setStyle(MainViewController.STATUS_DOT_CONNECTED_STYLE);
            root.applyCss();
            assertEquals(Color.web("#91B783"), dot.getTextFill(),
                    "已连接状态点应解析为主题成功色");
        });
    }

    private void fx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "JavaFX action timed out");
        if (failure.get() != null) throw new AssertionError("JavaFX action failed", failure.get());
    }

    private void assertPalette(String theme, Color canvas, Color control, Color primary,
                               Color field, Color fieldBorder, Color text, Color disabledText)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Button normal = new Button("Normal");
                Button primaryButton = new Button("Primary");
                primaryButton.getStyleClass().add("primary-button");
                Button disabled = new Button("Disabled");
                disabled.setDisable(true);
                TextField textField = new TextField("path");
                Label label = new Label("Label");
                VBox root = new VBox(8, normal, primaryButton, disabled, textField, label);
                Scene scene = new Scene(root, 480, 320);
                scene.getStylesheets().add(getClass().getResource(
                        "/com/runzhi/aishell/" + theme).toExternalForm());
                root.applyCss();
                root.layout();

                assertEquals(canvas, fill(root), theme + " canvas");
                assertEquals(control, fill(normal), theme + " normal button surface");
                assertEquals(primary, fill(primaryButton), theme + " primary button surface");
                assertTrue(isTransparent(fill(disabled)), theme + " disabled button must be transparent");
                assertEquals(field, fill(textField), theme + " input surface");
                assertEquals(fieldBorder, topBorder(textField), theme + " input border");
                assertEquals(text, label.getTextFill(), theme + " label text");
                assertEquals(disabledText, disabled.getTextFill(), theme + " disabled text");
                assertEquals(Color.WHITE, primaryButton.getTextFill(), theme + " primary text");
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "CSS apply timed out: " + theme);
        if (failure.get() != null) {
            throw new AssertionError("CSS palette assertion failed: " + theme, failure.get());
        }
    }

    private Color fill(Region region) {
        if (region.getBackground() == null || region.getBackground().getFills().isEmpty()) {
            return null;
        }
        return (Color) region.getBackground().getFills().get(0).getFill();
    }

    private boolean isTransparent(Color color) {
        return color != null && color.getOpacity() == 0;
    }

    private Color topBorder(Region region) {
        if (region.getBorder() == null || region.getBorder().getStrokes().isEmpty()) {
            return null;
        }
        return (Color) region.getBorder().getStrokes().get(0).getTopStroke();
    }
}
