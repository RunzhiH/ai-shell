/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh.domain;

import lombok.Data;

@Data
public class FileInfo {
    private String name;
    private String size;
    private String type; // 文件类型（DIR/FILE 等）
    private int permission;
    private Long modifiedTime;
    private String date; // 格式化的日期字符串

    public FileInfo(String name, String size, String type, int permission, Long modifiedTime) {
        this.name = name;
        this.size = size;
        this.type = type;
        this.permission = permission;
        this.modifiedTime = modifiedTime;
        // 格式化日期
        if (modifiedTime != null && modifiedTime > 0) {
            this.date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(modifiedTime * 1000));
        } else {
            this.date = "";
        }
    }
    
    // 兼容旧构造器
    public FileInfo(String name, String size, int permission, Long modifiedTime) {
        this(name, size, "", permission, modifiedTime);
    }
}
