/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import javafx.scene.control.Button;
import lombok.extern.slf4j.Slf4j;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.util.HashMap;
import java.util.Map;

/**
 * 按钮管理工具类，用于统一管理应用中的按钮功能
 */
@Slf4j
public class ButtonManager {
    
    private static volatile ButtonManager instance;
    private final Map<String, Object> buttonActions = new HashMap<>();
    
    private ButtonManager() {}
    
    public static synchronized ButtonManager getInstance() {
        if (instance == null) {
            instance = new ButtonManager();
        }
        return instance;
    }
    
    /**
     * 配置按钮的通用属性
     */
    public void configureButton(Button button, String tooltipText, String cssClass) {
        if (tooltipText != null && !tooltipText.isEmpty()) {
            button.setTooltip(new Tooltip(tooltipText));
        }
        
        if (cssClass != null && !cssClass.isEmpty()) {
            button.getStyleClass().add(cssClass);
        }
    }
    
    /**
     * 配置菜单项的快捷键
     */
    public void configureMenuItem(MenuItem menuItem, String accelerator) {
        if (menuItem == null || accelerator == null || accelerator.isEmpty()) return;
        //非法 accelerator 会让 KeyCode.valueOf 抛 IllegalArgumentException 使调用方法整体失败（2026-10-23 审计 UI-5）
        try {
            KeyCombination keyCombination = new KeyCodeCombination(KeyCode.valueOf(accelerator));
            menuItem.setAccelerator(keyCombination);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid accelerator '{}' for menu item, skipping: {}", accelerator, e.getMessage());
        }
    }
    
    /**
     * 注册按钮动作
     */
    public void registerAction(String actionId, Object action) {
        buttonActions.put(actionId, action);
    }
    
    /**
     * 获取注册的动作
     */
    @SuppressWarnings("unchecked")
    public <T> T getAction(String actionId, Class<T> clazz) {
        return (T) buttonActions.get(actionId);
    }
    
    /**
     * 移除注册的动作
     */
    public void removeAction(String actionId) {
        buttonActions.remove(actionId);
    }
}