/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */


package com.runzhi.aishell.common.exception;

import lombok.extern.slf4j.Slf4j;

/**
 * 统一异常处理类
 */
@Slf4j
public class AIShellException extends Exception {
    private final String errorCode;
    private final Object[] params;

    public AIShellException(String message) {
        super(message);
        this.errorCode = "UNKNOWN_ERROR";
        this.params = new Object[0];
    }

    public AIShellException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.params = new Object[0];
    }

    public AIShellException(String errorCode, String message, Object... params) {
        super(message);
        this.errorCode = errorCode;
        this.params = params;
    }

    public AIShellException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "UNKNOWN_ERROR";
        this.params = new Object[0];
    }

    public AIShellException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.params = new Object[0];
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object[] getParams() {
        return params;
    }

    /**
     * 静态方法：安全执行可能抛出异常的操作
     */
    public static <T> T safeExecute(SafeOperation<T> operation, T defaultValue) {
        try {
            return operation.execute();
        } catch (Exception e) {
            log.error("Safe execution failed", e);
            return defaultValue;
        }
    }

    @FunctionalInterface
    public interface SafeOperation<T> {
        T execute() throws Exception;
    }
}