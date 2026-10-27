/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.validation;

import com.runzhi.aishell.common.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;
import java.util.Collection;
import java.util.Map;

/**
 * 输入验证器
 * 提供统一的输入验证和边界检查功能
 */
@Slf4j
public class InputValidator {
    
    // 常用正则表达式模式
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])(\\.([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9]))*$"
    );
    
    private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile(
        "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );
    
    private static final Pattern PORT_PATTERN = Pattern.compile("^([1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$");
    
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]*$");
    
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^[\\x20-\\x7E]+$"); // ASCII可打印字符
    
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("^[^<>:\"|?*]+$"); // Windows文件路径非法字符
    
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^[\\x20-\\x7E\\u4e00-\\u9fa5]+$"); // ASCII和中文字符
    
    // 私有构造函数防止实例化
    private InputValidator() {}
    
    /**
     * 验证主机名
     */
    public static void validateHostname(String hostname, String fieldName) {
        if (hostname == null || hostname.trim().isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (hostname.length() > 253) {
            throw new ValidationException(fieldName + "长度不能超过253个字符");
        }
        if (!HOSTNAME_PATTERN.matcher(hostname).matches()) {
            throw new ValidationException(fieldName + "格式不正确");
        }
    }
    
    /**
     * 验证IP地址
     */
    public static void validateIpAddress(String ip, String fieldName) {
        if (ip == null || ip.trim().isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (!IP_ADDRESS_PATTERN.matcher(ip).matches()) {
            throw new ValidationException(fieldName + "必须是有效的IPv4地址");
        }
    }
    
    /**
     * 验证端口号
     */
    public static void validatePort(int port, String fieldName) {
        if (port < 1 || port > 65535) {
            throw new ValidationException(fieldName + "必须在1-65535范围内");
        }
    }
    
    /**
     * 验证用户名
     */
    public static void validateUsername(String username, String fieldName) {
        if (username == null || username.trim().isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (username.length() > 32) {
            throw new ValidationException(fieldName + "长度不能超过32个字符");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ValidationException(fieldName + "只能包含字母、数字、下划线和连字符，且必须以字母或下划线开头");
        }
    }
    
    /**
     * 验证密码
     */
    public static void validatePassword(String password, String fieldName) {
        if (password == null || password.isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (password.length() < 6) {
            throw new ValidationException(fieldName + "长度不能少于6个字符");
        }
        if (password.length() > 128) {
            throw new ValidationException(fieldName + "长度不能超过128个字符");
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new ValidationException(fieldName + "包含无效字符");
        }
    }
    
    /**
     * 验证文件路径
     */
    public static void validateFilePath(String filePath, String fieldName) {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (filePath.length() > 260) {
            throw new ValidationException(fieldName + "长度不能超过260个字符");
        }
        if (!FILE_PATH_PATTERN.matcher(filePath).matches()) {
            throw new ValidationException(fieldName + "包含非法字符");
        }
    }
    
    /**
     * 验证命令字符串
     */
    public static void validateCommand(String command, String fieldName) {
        if (command == null || command.trim().isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (command.length() > 4096) {
            throw new ValidationException(fieldName + "长度不能超过4096个字符");
        }
        if (!COMMAND_PATTERN.matcher(command).matches()) {
            throw new ValidationException(fieldName + "包含无效字符");
        }
    }
    
    /**
     * 验证集合不为空
     */
    public static void validateNotEmpty(Collection<?> collection, String fieldName) {
        if (collection == null || collection.isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
    }
    
    /**
     * 验证映射不为空
     */
    public static void validateNotEmpty(Map<?, ?> map, String fieldName) {
        if (map == null || map.isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
    }
    
    /**
     * 验证字符串不为空
     */
    public static void validateNotEmpty(String str, String fieldName) {
        if (str == null || str.trim().isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
    }
    
    /**
     * 验证对象不为null
     */
    public static void validateNotNull(Object obj, String fieldName) {
        if (obj == null) {
            throw new ValidationException(fieldName + "不能为空");
        }
    }
    
    /**
     * 验证数值范围
     */
    public static void validateRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new ValidationException(fieldName + "必须在" + min + "-" + max + "范围内");
        }
    }
    
    /**
     * 验证数值范围（长整型）
     */
    public static void validateRange(long value, long min, long max, String fieldName) {
        if (value < min || value > max) {
            throw new ValidationException(fieldName + "必须在" + min + "-" + max + "范围内");
        }
    }
    
    /**
     * 验证数值范围（双精度）
     */
    public static void validateRange(double value, double min, double max, String fieldName) {
        if (value < min || value > max) {
            throw new ValidationException(fieldName + "必须在" + min + "-" + max + "范围内");
        }
    }
    
    /**
     * 安全的字符串截取，防止越界
     */
    public static String safeSubstring(String str, int beginIndex, int endIndex) {
        if (str == null) {
            return null;
        }
        if (beginIndex < 0) {
            beginIndex = 0;
        }
        if (endIndex > str.length()) {
            endIndex = str.length();
        }
        if (beginIndex >= endIndex) {
            return "";
        }
        return str.substring(beginIndex, endIndex);
    }
    
    /**
     * 安全的数组访问，防止越界
     */
    public static <T> T safeArrayAccess(T[] array, int index, T defaultValue) {
        if (array == null || index < 0 || index >= array.length) {
            return defaultValue;
        }
        return array[index];
    }
    
    /**
     * 安全的集合访问，防止越界
     */
    public static <T> T safeCollectionAccess(Collection<T> collection, int index, T defaultValue) {
        if (collection == null || collection.isEmpty() || index < 0 || index >= collection.size()) {
            return defaultValue;
        }
        
        if (collection instanceof java.util.List) {
            try {
                return ((java.util.List<T>) collection).get(index);
            } catch (IndexOutOfBoundsException e) {
                return defaultValue;
            }
        }
        
        int i = 0;
        for (T item : collection) {
            if (i == index) {
                return item;
            }
            i++;
        }
        return defaultValue;
    }
    
    /**
     * 安全的映射访问，提供默认值
     */
    public static <K, V> V safeMapAccess(Map<K, V> map, K key, V defaultValue) {
        if (map == null || key == null) {
            return defaultValue;
        }
        V value = map.get(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 验证并清理输入字符串
     */
    public static String sanitizeInput(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        
        // 限制长度
        String sanitized = input.length() > maxLength ? input.substring(0, maxLength) : input;
        
        // 移除控制字符（保留换行和制表符）
        sanitized = sanitized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        
        return sanitized.trim();
    }
    
    /**
     * 验证邮箱地址格式
     */
    public static void validateEmail(String email, String fieldName) {
        if (email == null || email.trim().isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
        
        String emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        if (!Pattern.matches(emailRegex, email)) {
            throw new ValidationException(fieldName + "格式不正确");
        }
        
        if (email.length() > 254) {
            throw new ValidationException(fieldName + "长度不能超过254个字符");
        }
    }
    
    /**
     * 验证URL格式
     */
    public static void validateUrl(String url, String fieldName) {
        if (url == null || url.trim().isEmpty()) {
            throw new ValidationException(fieldName + "不能为空");
        }
        
        try {
            java.net.URI uri = new java.net.URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new ValidationException(fieldName + "必须包含协议和主机名");
            }
        } catch (java.net.URISyntaxException e) {
            throw new ValidationException(fieldName + "格式不正确");
        }
        
        if (url.length() > 2048) {
            throw new ValidationException(fieldName + "长度不能超过2048个字符");
        }
    }
    
    /**
     * 检查字符串长度
     */
    public static void checkStringLength(String str, String fieldName, int maxLength) {
        if (str == null) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (str.length() > maxLength) {
            throw new ValidationException(fieldName + "长度超出限制(" + maxLength + "字符)");
        }
    }
}