/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import com.google.gson.*;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * LocalDateTime的Gson适配器
 * 用于解决Java 17+模块系统下Gson无法访问LocalDateTime内部字段的问题
 */
public class LocalDateTimeAdapter implements JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public JsonElement serialize(LocalDateTime localDateTime, Type srcType, JsonSerializationContext context) {
        if (localDateTime == null) {
            return null;
        }
        return new JsonPrimitive(localDateTime.format(FORMATTER));
    }

    @Override
    public LocalDateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) 
            throws JsonParseException {
        if (json == null || json.isJsonNull()) {
            return null;
        }
        
        String dateString = json.getAsString();
        try {
            return LocalDateTime.parse(dateString, FORMATTER);
        } catch (DateTimeParseException e) {
            // 尝试解析ISO格式的日期时间
            try {
                return LocalDateTime.parse(dateString);
            } catch (DateTimeParseException ex) {
                throw new JsonParseException("Unable to parse date: " + dateString, ex);
            }
        }
    }
}