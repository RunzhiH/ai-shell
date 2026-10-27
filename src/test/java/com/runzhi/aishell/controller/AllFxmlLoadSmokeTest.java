package com.runzhi.aishell.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全量 FXML 冒烟测试。
 *
 * <p>背景：FXML 不参与 javac 编译，缺失 import（如 {@code <?import java.lang.String?>}）、
 * 写错类型名、引用不存在的 fx:id 等问题只有在运行时真正打开界面才会暴露。
 * 本测试在 FX 线程上把 {@code src/main/resources} 下所有 FXML 全部加载一遍，
 * 让这类"编译器看不见"的界面缺陷在 CI 阶段就失败。</p>
 *
 * <p>这是 2026-09 实测应用时发现 settings-ssh.fxml 连缺两个 import 后加的防护网。</p>
 */
class AllFxmlLoadSmokeTest {

    /**
     * 无 headless 限制环境无法加载的 FXML 及其正当理由。
     * 每条都必须写清原因，避免清单沦为免检通行证。
     */
    private static final Set<String> KNOWN_HEADLESS_LIMITATIONS = new HashSet<>(Arrays.asList(
            // RichTextFX 的 InlineCssTextArea 在无显示器环境构造时抛
            // ArrayIndexOutOfBoundsException: Index 10 out of bounds for length 2
            "/com/runzhi/aishell/terminal-tab.fxml"
    ));

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
    void everyLoadableFxmlLoadsOnFxThread() throws Exception {
        List<String> failures = new ArrayList<>();
        int loaded = 0;

        for (String resource : sortedResources()) {
            if (KNOWN_HEADLESS_LIMITATIONS.contains(resource)) {
                continue;
            }
            try {
                Parent root = loadOnFxThread(resource);
                assertNotNull(root, "加载返回 null：" + resource);
                loaded++;
            } catch (AssertionError e) {
                failures.add(resource + " -> " + e.getMessage());
            }
        }

        assertTrue(failures.isEmpty(),
                loaded + " 个 FXML 加载成功，但以下 " + failures.size() + " 个失败：\n"
                        + String.join("\n", failures));
    }

    /**
     * 单独钉住 settings-ssh.fxml：它曾连续缺失 FXCollections 与 String 两个 import，
     * 错误是短路式的——修好第一个才暴露第二个。显式断言防止回归。
     *
     * <p>用 FXMLLoader 实例加载以拿到 controller，直接验证 @FXML 字段注入成功——
     * 节点未挂到场景图时 Parent.lookup 可能查不到 TitledPane 内的控件，故不使用 lookup。</p>
     */
    @Test
    void settingsSshLoadsAfterDoubleImportFix() throws Exception {
        String resource = "/com/runzhi/aishell/settings-ssh.fxml";
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(resource));
                loader.setResources(com.runzhi.aishell.common.i18n.I18n.bundle());
                Parent root = loader.load();
                assertNotNull(root, "root 为空");
                com.runzhi.aishell.controller.SettingsSSHController controller = loader.getController();
                assertNotNull(controller, "controller 未注入");
                assertNotNull(loader.getNamespace().get("defaultPortSpinner"), "defaultPortSpinner 未注入");
                assertNotNull(loader.getNamespace().get("authMethodCombo"), "authMethodCombo 未注入");
                assertNotNull(loader.getNamespace().get("logLevelCombo"), "logLevelCombo 未注入");
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "加载超时：" + resource);
        if (failure.get() != null) {
            throw new AssertionError("settings-ssh.fxml 加载或断言失败", failure.get());
        }
    }

    /**
     * 所有 FXML 里用到的 %key 都必须同时存在于中英两个 resource bundle，
     * 否则切换语言后界面会显示原始 key 或抛 MissingResourceException。
     */
    @Test
    void everyPercentKeyExistsInBothBundles() throws Exception {
        java.util.Locale original = com.runzhi.aishell.common.i18n.I18n.current();
        List<String> missing = new ArrayList<>();
        try {
            for (java.util.Locale locale : new java.util.Locale[]{
                    java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.ENGLISH}) {
                com.runzhi.aishell.common.i18n.I18n.setLocale(locale, false);
                java.util.ResourceBundle bundle = com.runzhi.aishell.common.i18n.I18n.bundle();
                for (String resource : sortedResources()) {
                    String content = java.nio.file.Files.readString(
                            java.nio.file.Path.of("src/main/resources" + resource));
                    // 去掉 FXML 注释，避免把注释里的 %key 误判为缺失
                    String stripped = content.replaceAll("<!--[\\s\\S]*?-->", "");
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("%([A-Za-z][\\w.]*)").matcher(stripped);
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        if (!bundle.containsKey(key)) {
                            missing.add(locale + " 缺少 " + key + "（" + resource + "）");
                        }
                    }
                }
            }
        } finally {
            com.runzhi.aishell.common.i18n.I18n.setLocale(original, false);
        }
        assertTrue(missing.isEmpty(),
                missing.size() + " 处 %key 找不到对应翻译：\n" + String.join("\n", missing));
    }

    private Set<String> discoverFxmlResources() throws Exception {
        File resourcesDir = new File("src/main/resources/com/runzhi/aishell");
        assertTrue(resourcesDir.isDirectory(), "资源目录不存在：" + resourcesDir.getAbsolutePath());
        Set<String> found = new HashSet<>();
        try (Stream<File> walk = Arrays.stream(
                resourcesDir.listFiles((dir, name) -> name.endsWith(".fxml")))) {
            walk.forEach(f -> found.add("/com/runzhi/aishell/" + f.getName()));
        }
        assertFalse(found.isEmpty(), "未发现任何 FXML 文件，扫描逻辑可能已失效");
        return found;
    }

    private List<String> sortedResources() throws Exception {
        return new ArrayList<>(discoverFxmlResources());
    }

    private Parent loadOnFxThread(String resource) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Parent> root = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(resource));
                loader.setResources(com.runzhi.aishell.common.i18n.I18n.bundle());
                root.set(loader.load());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "FXML load timed out: " + resource);
        if (failure.get() != null) {
            throw new AssertionError("FXML load failed: " + resource, failure.get());
        }
        return root.get();
    }
}
