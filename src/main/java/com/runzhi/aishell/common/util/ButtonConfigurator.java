/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * 按钮配置器，提供统一的按钮配置功能
 */
public class ButtonConfigurator {
    
    private static volatile ButtonConfigurator instance;
    
    private ButtonConfigurator() {}
    
    public static synchronized ButtonConfigurator getInstance() {
        if (instance == null) {
            instance = new ButtonConfigurator();
        }
        return instance;
    }
    
    /**
     * 配置按钮的基本属性
     */
    public void configureButton(Button button, String text, String tooltip, String... styleClasses) {
        if (text != null) {
            button.setText(text);
        }
        
        if (tooltip != null && !tooltip.isEmpty()) {
            button.setTooltip(new Tooltip(tooltip));
        }
        
        if (styleClasses != null) {
            for (String styleClass : styleClasses) {
                if (styleClass != null && !styleClass.isEmpty()) {
                    button.getStyleClass().add(styleClass);
                }
            }
        }
    }
    
    /**
     * 配置菜单项的快捷键
     */
    public void configureMenuItem(MenuItem menuItem, String accelerator) {
        if (accelerator != null && !accelerator.isEmpty()) {
            // 解析快捷键字符串，例如 "Ctrl+N" 或 "Ctrl+Shift+S"
            KeyCombination keyCombination = parseKeyCombination(accelerator);
            if (keyCombination != null) {
                menuItem.setAccelerator(keyCombination);
            }
        }
    }
    
    /**
     * 解析快捷键字符串
     */
    private KeyCombination parseKeyCombination(String accelerator) {
        try {
            // 分割快捷键字符串，例如 "Ctrl+N" -> ["Ctrl", "N"]
            String[] parts = accelerator.toUpperCase().split("\\+");
            
            KeyCode keyCode = null;
            boolean ctrl = false, alt = false, shift = false, meta = false;
            
            for (String part : parts) {
                part = part.trim();
                switch (part) {
                    case "CTRL":
                        ctrl = true;
                        break;
                    case "ALT":
                        alt = true;
                        break;
                    case "SHIFT":
                        shift = true;
                        break;
                    case "META":
                        meta = true;
                        break;
                    default:
                        // 尝试解析为KeyCode
                        try {
                            keyCode = KeyCode.valueOf(part);
                        } catch (IllegalArgumentException e) {
                            System.err.println("无法识别的按键: " + part);
                        }
                        break;
                }
            }
            
            if (keyCode != null) {
                // 收集所有激活的修饰符
                java.util.List<KeyCombination.Modifier> modifiers = new java.util.ArrayList<>();
                if (ctrl) modifiers.add(KeyCombination.CONTROL_DOWN);
                if (alt) modifiers.add(KeyCombination.ALT_DOWN);
                if (shift) modifiers.add(KeyCombination.SHIFT_DOWN);
                if (meta) modifiers.add(KeyCombination.META_DOWN);
                
                // 创建KeyCodeCombination，使用收集的修饰符数组
                KeyCombination.Modifier[] modifierArray = modifiers.toArray(new KeyCombination.Modifier[0]);
                return new KeyCodeCombination(keyCode, modifierArray);
            }
        } catch (Exception e) {
            System.err.println("解析快捷键失败: " + accelerator + ", 错误: " + e.getMessage());
        }
        
        return null;
    }
}