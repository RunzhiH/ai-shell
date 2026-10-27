/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.i18n;

import com.runzhi.aishell.controller.SettingsGeneralController;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * 界面语言支持：统一管理当前 Locale 与资源包，供 FXML（%key）与 Java 代码（tr）共用。
 *
 * <p>语言偏好沿用设置页历史存储位置（Preferences 中 general.language，值为显示名），
 * 因此旧版本保存过的选择无缝生效。缺键时 tr 返回键本身、英文包回退到中文包，绝不让 UI 崩溃。
 *
 * <p><b>实现说明</b>：本应用是 JPMS 命名模块，{@code ResourceBundle.Control}
 * 在命名模块下会抛 UnsupportedOperationException，因此这里直接按 UTF-8 手工
 * 加载 properties 并包成 ResourceBundle（Java 默认按 ISO-8859-1 解码，中文需显式指定）。
 */
public final class I18n {

    private static final String ZH_RESOURCE = "com/runzhi/aishell/i18n/messages_zh.properties";
    private static final String EN_RESOURCE = "com/runzhi/aishell/i18n/messages_en.properties";
    private static final String PREF_KEY = "general.language";

    /** 与 SettingsGeneralController 共用同一 Preferences 节点，沿用历史存储位置 */
    private static final Preferences PREFS = Preferences.userNodeForPackage(SettingsGeneralController.class);

    /** 设置页下拉项（显示名）与 Locale 的对应，语言名固定用各自语言书写 */
    private static final Map<String, Locale> LABEL_TO_LOCALE = Map.of(
            "简体中文", Locale.SIMPLIFIED_CHINESE,
            "English", Locale.ENGLISH);

    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    private static volatile Locale current = DEFAULT_LOCALE;
    private static volatile ResourceBundle bundle = bundleFor(DEFAULT_LOCALE);

    private static final CopyOnWriteArrayList<Consumer<Locale>> listeners = new CopyOnWriteArrayList<>();

    static {
        // 启动时读取已保存的语言偏好
        String storedLabel = PREFS.get(PREF_KEY, null);
        Locale stored = storedLabel == null ? null : LABEL_TO_LOCALE.get(storedLabel);
        if (stored != null) {
            applyLocale(stored);
        }
    }

    private I18n() {
    }

    /**
     * 翻译键值。缺键时返回键本身，保证 UI 永远拿得到字符串。
     *
     * @param key  资源键（如 "fm.refresh"）
     * @param args MessageFormat 参数
     * @return 翻译文本
     */
    public static String tr(String key, Object... args) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
        return args == null || args.length == 0 ? pattern : MessageFormat.format(pattern, args);
    }

    /** FXML 加载时注入用 */
    public static ResourceBundle bundle() {
        return bundle;
    }

    public static Locale current() {
        return current;
    }

    /** 当前语言的显示名（设置页回显用） */
    public static String currentLabel() {
        return labelFor(current);
    }

    /** 当前语言是否为英文 */
    public static boolean isEnglish() {
        return "en".equals(current.getLanguage());
    }

    /**
     * 按设置页显示名切换语言。
     *
     * @return 语言是否真的发生变化
     */
    public static boolean setByLabel(String label) {
        Locale locale = LABEL_TO_LOCALE.getOrDefault(label, DEFAULT_LOCALE);
        return setLocale(locale, true);
    }

    /**
     * 切换语言。
     *
     * @param persist 是否写入 Preferences
     * @return 语言是否真的发生变化
     */
    public static boolean setLocale(Locale locale, boolean persist) {
        Objects.requireNonNull(locale, "locale");
        if (locale.equals(current)) {
            return false;
        }
        if (persist) {
            PREFS.put(PREF_KEY, labelFor(locale));
        }
        applyLocale(locale);
        for (Consumer<Locale> listener : listeners) {
            listener.accept(current);
        }
        return true;
    }

    /** 语言变化的监听（切语言后需重建的视图注册） */
    public static void addListener(Consumer<Locale> listener) {
        listeners.add(listener);
    }

    public static void removeListener(Consumer<Locale> listener) {
        listeners.remove(listener);
    }

    private static void applyLocale(Locale locale) {
        current = locale;
        bundle = bundleFor(locale);
    }

    private static String labelFor(Locale locale) {
        return "en".equals(locale.getLanguage()) ? "English" : "简体中文";
    }

    /**
     * 组装指定语言的资源包：英文包找不到的键回退到中文包，中文包是永远存在的兜底。
     */
    private static ResourceBundle bundleFor(Locale locale) {
        ResourceBundle zh = loadProperties(ZH_RESOURCE);
        if (zh == null) {
            // 连中文包都缺失属于打包错误，给空包避免 NPE，界面退化为显示键名
            return emptyBundle();
        }
        if (!"en".equals(locale.getLanguage())) {
            return zh;
        }
        ResourceBundle en = loadProperties(EN_RESOURCE);
        if (en == null) {
            return zh;
        }
        return withParent(en, zh);
    }

    private static ResourceBundle withParent(ResourceBundle child, ResourceBundle parent) {
        return new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                try {
                    return child.getObject(key);
                } catch (MissingResourceException notInChild) {
                    // no-op
                }
                try {
                    return parent.getObject(key);
                } catch (MissingResourceException notInParent) {
                    return null;
                }
            }

            @Override
            public Enumeration<String> getKeys() {
                return Collections.enumeration(
                        java.util.stream.Stream.concat(
                                        child.keySet().stream(), parent.keySet().stream())
                                .distinct()
                                .toList());
            }

            @Override
            public boolean containsKey(String key) {
                return child.containsKey(key) || parent.containsKey(key);
            }
        };
    }

    private static ResourceBundle emptyBundle() {
        return new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                return null;
            }

            @Override
            public Enumeration<String> getKeys() {
                return Collections.emptyEnumeration();
            }
        };
    }

    /**
     * UTF-8 读取 properties 并包成 ResourceBundle。
     *
     * <p>JPMS 下 {@link ClassLoader#getResourceAsStream} 拿不到命名模块内部资源，
     * 必须用 {@link Class#getResourceAsStream}（模块感知，本模块自身资源无需 opens）。
     */
    private static ResourceBundle loadProperties(String resource) {
        try (InputStream stream = I18n.class.getResourceAsStream("/" + resource)) {
            if (stream == null) {
                return null;
            }
            Properties props = new Properties();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            return new ResourceBundle() {
                @Override
                protected Object handleGetObject(String key) {
                    return props.getProperty(key);
                }

                @Override
                public Enumeration<String> getKeys() {
                    return Collections.enumeration(props.stringPropertyNames());
                }
            };
        } catch (IOException e) {
            return null;
        }
    }
}
