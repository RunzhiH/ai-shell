/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.enhancement;

import com.runzhi.aishell.terminal.enhancement.modules.EnhancedThemeModule;
import com.runzhi.aishell.terminal.enhancement.modules.EnhancedThemeModule.TerminalTheme;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.Arrays;
import javafx.scene.control.Tab;
import com.runzhi.aishell.terminal.InteractiveTerminalView;

/**
 * 主题增强器 - UI 工具类
 * 提供终端主题选择的 UI 组件，实际主题管理由 EnhancedThemeModule 负责
 */
public class ThemeEnhancer {

    /**
     * 创建主题选择器 UI 组件
     * @param themeModule 主题模块实例
     * @param outputArea 输出区域
     * @param tabId 标签页 ID(可选)
     * @return VBox 容器
     */
    public static VBox createThemeSelector(EnhancedThemeModule themeModule, 
                                           InlineCssTextArea outputArea, 
                                           String tabId) {
        return createThemeSelector(themeModule, outputArea, tabId, null);
    }

    public static VBox createThemeSelector(EnhancedThemeModule themeModule,
                                           InlineCssTextArea outputArea,
                                           String tabId,
                                           Tab tab) {
        VBox container = new VBox(10);
        Label titleLabel = new Label("选择终端主题:");
        container.getChildren().add(titleLabel);

        ToggleGroup themeGroup = new ToggleGroup();

        for (TerminalTheme theme : TerminalTheme.values()) {
            RadioButton radioButton = new RadioButton(theme.getDisplayName());
            radioButton.setToggleGroup(themeGroup);
            container.getChildren().add(radioButton);
        }

        // 设置当前主题为选中状态
        TerminalTheme currentTheme = (tabId != null) ? 
            themeModule.getTabTheme(tabId) : themeModule.getDefaultTheme();
        for (Toggle toggle : themeGroup.getToggles()) {
            RadioButton rb = (RadioButton) toggle;
            if (rb.getText().equals(currentTheme.getDisplayName())) {
                rb.setSelected(true);
            }
        }

        Button applyButton = new Button("应用主题");
        applyButton.setOnAction(event -> {
            Toggle selectedToggle = themeGroup.getSelectedToggle();
            if (selectedToggle != null) {
                RadioButton selectedRadio = (RadioButton) selectedToggle;
                String selectedThemeName = selectedRadio.getText();

                // 根据显示名称找到对应的枚举值
                TerminalTheme selectedTheme = Arrays.stream(TerminalTheme.values())
                        .filter(theme -> theme.getDisplayName().equals(selectedThemeName))
                        .findFirst()
                        .orElse(TerminalTheme.DARK_TERMINAL);

                // 应用主题
                if (tabId != null) {
                    themeModule.setTabTheme(tabId, selectedTheme);
                } else {
                    themeModule.setDefaultTheme(selectedTheme);
                }
                
                if (outputArea != null) {
                    themeModule.applyTheme(outputArea, selectedTheme);
                }
                if (tab != null) {
                    InteractiveTerminalView.applyTheme(tab, selectedTheme);
                }
            }
        });

        container.getChildren().add(applyButton);

        return container;
    }

    /**
     * 获取默认主题
     */
    public static TerminalTheme getDefaultTheme() {
        return TerminalTheme.DARK_TERMINAL;
    }
}
