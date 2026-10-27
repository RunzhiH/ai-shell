/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.features.bookmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.runzhi.aishell.common.config.AppConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 书签管理器 - 单例模式
 */
public class BookmarkManager {

    private static final Logger log = LoggerFactory.getLogger(BookmarkManager.class);
    private static volatile BookmarkManager instance;
    private final List<CommandBookmark> bookmarks;
    private final List<BookmarkListener> listeners;
    private final Gson gson;
    private Path configPath;
    
    private BookmarkManager() {
        this.bookmarks = new CopyOnWriteArrayList<>();
        this.listeners = new ArrayList<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        initialize();
    }
    
    public static BookmarkManager getInstance() {
        if (instance == null) {
            synchronized (BookmarkManager.class) {
                if (instance == null) {
                    instance = new BookmarkManager();
                }
            }
        }
        return instance;
    }
    
    private void initialize() {
        try {
            configPath = java.nio.file.Paths.get(AppConstants.DEFAULT_CONFIG_PATH.replace("ai_configs.json", "bookmarks.json"));
            loadBookmarks();
        } catch (Exception e) {
            log.error("初始化书签管理器失败", e);
            bookmarks.clear();
        }
    }
    
    /**
     * 添加书签
     */
    public void addBookmark(CommandBookmark bookmark) {
        if (!bookmarks.contains(bookmark)) {
            bookmarks.add(bookmark);
            saveBookmarks();
            notifyBookmarksChanged();
        }
    }
    
    /**
     * 删除书签
     */
    public void removeBookmark(String bookmarkId) {
        Optional<CommandBookmark> bookmarkOpt = getBookmarkById(bookmarkId);
        if (bookmarkOpt.isPresent()) {
            bookmarks.remove(bookmarkOpt.get());
            saveBookmarks();
            notifyBookmarksChanged();
        }
    }
    
    /**
     * 更新书签
     */
    public void updateBookmark(CommandBookmark bookmark) {
        Optional<CommandBookmark> existingOpt = getBookmarkById(bookmark.getId());
        if (existingOpt.isPresent()) {
            CommandBookmark existing = existingOpt.get();
            existing.setName(bookmark.getName());
            existing.setCommand(bookmark.getCommand());
            existing.setDescription(bookmark.getDescription());
            existing.setCategory(bookmark.getCategory());
            existing.setTags(bookmark.getTags());
            existing.setEnabled(bookmark.isEnabled());
            saveBookmarks();
            notifyBookmarksChanged();
        }
    }
    
    /**
     * 根据 ID 获取书签
     */
    public Optional<CommandBookmark> getBookmarkById(String id) {
        return bookmarks.stream()
                .filter(b -> b.getId().equals(id))
                .findFirst();
    }
    
    /**
     * 根据分类获取书签
     */
    public List<CommandBookmark> getBookmarksByCategory(String category) {
        return bookmarks.stream()
                .filter(b -> b.isEnabled() && category.equals(b.getCategory()))
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有书签
     */
    public List<CommandBookmark> getAllBookmarks() {
        return bookmarks.stream()
                .filter(CommandBookmark::isEnabled)
                .collect(Collectors.toList());
    }
    
    /**
     * 搜索书签
     */
    public List<CommandBookmark> searchBookmarks(String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        return bookmarks.stream()
                .filter(b -> b.isEnabled())
                .filter(b -> b.getName().toLowerCase().contains(lowerKeyword) ||
                        b.getCommand().toLowerCase().contains(lowerKeyword) ||
                        b.getDescription().toLowerCase().contains(lowerKeyword) ||
                        (b.getTags() != null && b.getTags().toLowerCase().contains(lowerKeyword)))
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有分类
     */
    public Set<String> getCategories() {
        return bookmarks.stream()
                .map(CommandBookmark::getCategory)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
    
    /**
     * 执行书签命令（增加使用计数）
     */
    public void executeBookmark(String bookmarkId) {
        getBookmarkById(bookmarkId).ifPresent(bookmark -> {
            bookmark.incrementUsageCount();
            saveBookmarks();
        });
    }
    
    /**
     * 添加监听器
     */
    public void addListener(BookmarkListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 移除监听器
     */
    public void removeListener(BookmarkListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyBookmarksChanged() {
        for (BookmarkListener listener : listeners) {
            try {
                listener.onBookmarksChanged();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 保存书签到文件
     */
    private void saveBookmarks() {
        try {
            Files.createDirectories(configPath.getParent());
            String json = gson.toJson(bookmarks);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            log.error("保存书签失败", e);
        }
    }
    
    /**
     * 从文件加载书签
     */
    private void loadBookmarks() {
        try {
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                CommandBookmark[] loaded = gson.fromJson(json, CommandBookmark[].class);
                bookmarks.clear();
                bookmarks.addAll(Arrays.asList(loaded));
            } else {
                // 加载默认书签
                loadDefaultBookmarks();
            }
        } catch (IOException e) {
            log.error("加载书签失败", e);
            loadDefaultBookmarks();
        }
    }
    
    /**
     * 加载默认书签
     */
    private void loadDefaultBookmarks() {
        List<CommandBookmark> defaults = new ArrayList<>();
        
        // 系统监控类
        defaults.add(new CommandBookmark("查看 CPU 信息", "lscpu", "系统监控"));
        defaults.add(new CommandBookmark("查看内存使用", "free -h", "系统监控"));
        defaults.add(new CommandBookmark("查看磁盘空间", "df -h", "系统监控"));
        defaults.add(new CommandBookmark("查看网络接口", "ip addr show", "系统监控"));
        defaults.add(new CommandBookmark("查看进程 TOP10", "ps aux --sort=-%cpu | head -11", "系统监控"));
        
        // 文件操作类
        defaults.add(new CommandBookmark("查找大文件", "find . -type f -size +100M -exec ls -lh {} \\;", "文件操作"));
        defaults.add(new CommandBookmark("统计文件数量", "find . -type f | wc -l", "文件操作"));
        defaults.add(new CommandBookmark("查看当前目录大小", "du -sh *", "文件操作"));
        
        // 网络工具类
        defaults.add(new CommandBookmark("测试网络连接", "ping -c 4 google.com", "网络工具"));
        defaults.add(new CommandBookmark("查看端口占用", "netstat -tulpn", "网络工具"));
        defaults.add(new CommandBookmark("DNS 查询", "dig example.com", "网络工具"));
        
        // 系统维护类
        defaults.add(new CommandBookmark("清理 apt 缓存", "sudo apt clean && sudo apt autoclean", "系统维护"));
        defaults.add(new CommandBookmark("查看系统日志", "journalctl -xe", "系统维护"));
        defaults.add(new CommandBookmark("重启服务", "sudo systemctl restart service-name", "系统维护"));
        
        bookmarks.addAll(defaults);
        saveBookmarks();
    }
    
    /**
     * 书签变化监听器
     */
    public interface BookmarkListener {
        void onBookmarksChanged();
    }
}
