/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.features.bookmark;

import java.time.LocalDateTime;

/**
 * 命令书签数据模型
 */
public class CommandBookmark {
    
    private String id;
    private String name;
    private String command;
    private String description;
    private String category;
    private String tags;
    private LocalDateTime createdTime;
    private LocalDateTime lastUsedTime;
    private int usageCount;
    private boolean enabled;
    
    public CommandBookmark() {
        this.id = generateId();
        this.createdTime = LocalDateTime.now();
        this.enabled = true;
        this.usageCount = 0;
    }
    
    public CommandBookmark(String name, String command, String category) {
        this();
        this.name = name;
        this.command = command;
        this.category = category;
    }
    
    private String generateId() {
        return "bookmark_" + System.currentTimeMillis() + "_" + Math.random();
    }
    
    // Getters and Setters
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getCommand() {
        return command;
    }
    
    public void setCommand(String command) {
        this.command = command;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getCategory() {
        return category;
    }
    
    public void setCategory(String category) {
        this.category = category;
    }
    
    public String getTags() {
        return tags;
    }
    
    public void setTags(String tags) {
        this.tags = tags;
    }
    
    public LocalDateTime getCreatedTime() {
        return createdTime;
    }
    
    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }
    
    public LocalDateTime getLastUsedTime() {
        return lastUsedTime;
    }
    
    public void setLastUsedTime(LocalDateTime lastUsedTime) {
        this.lastUsedTime = lastUsedTime;
    }
    
    public int getUsageCount() {
        return usageCount;
    }
    
    public void setUsageCount(int usageCount) {
        this.usageCount = usageCount;
    }
    
    public void incrementUsageCount() {
        this.usageCount++;
        this.lastUsedTime = LocalDateTime.now();
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    @Override
    public String toString() {
        return name;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        CommandBookmark that = (CommandBookmark) obj;
        return id.equals(that.id);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
}