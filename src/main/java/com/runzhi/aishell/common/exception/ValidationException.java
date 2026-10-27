/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.exception;

/**
 * 验证异常类
 * 用于输入验证和边界检查时抛出的异常
 */
public class ValidationException extends RuntimeException {
    
    private final String fieldName;
    private final Object invalidValue;
    
    public ValidationException(String message) {
        super(message);
        this.fieldName = null;
        this.invalidValue = null;
    }
    
    public ValidationException(String fieldName, String message) {
        super(buildMessage(fieldName, message));
        this.fieldName = fieldName;
        this.invalidValue = null;
    }
    
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldName = null;
        this.invalidValue = null;
    }
    
    public ValidationException(String fieldName, String message, Object invalidValue) {
        super(buildMessage(fieldName, message));
        this.fieldName = fieldName;
        this.invalidValue = invalidValue;
    }
    
    public ValidationException(String fieldName, String message, Object invalidValue, Throwable cause) {
        super(buildMessage(fieldName, message), cause);
        this.fieldName = fieldName;
        this.invalidValue = invalidValue;
    }
    
    private static String buildMessage(String fieldName, String message) {
        if (fieldName != null && !fieldName.isEmpty()) {
            return fieldName + ": " + message;
        }
        return message;
    }
    
    public String getFieldName() {
        return fieldName;
    }
    
    public Object getInvalidValue() {
        return invalidValue;
    }
    
    /**
     * 创建字段缺失异常
     */
    public static ValidationException missingField(String fieldName) {
        return new ValidationException(fieldName, "字段不能为空");
    }
    
    /**
     * 创建字段格式错误异常
     */
    public static ValidationException invalidFormat(String fieldName, String expectedFormat) {
        return new ValidationException(fieldName, "格式不正确，期望格式: " + expectedFormat);
    }
    
    /**
     * 创建字段超出范围异常
     */
    public static ValidationException outOfRange(String fieldName, Object value, Object min, Object max) {
        return new ValidationException(fieldName, 
            String.format("值 %s 超出范围 [%s, %s]", value, min, max), value);
    }
    
    /**
     * 创建字段长度超出限制异常
     */
    public static ValidationException lengthExceeded(String fieldName, int actualLength, int maxLength) {
        return new ValidationException(fieldName, 
            String.format("长度 %d 超出最大限制 %d", actualLength, maxLength));
    }
    
    /**
     * 创建字段包含非法字符异常
     */
    public static ValidationException illegalCharacters(String fieldName, String illegalChars) {
        return new ValidationException(fieldName, "包含非法字符: " + illegalChars);
    }
}