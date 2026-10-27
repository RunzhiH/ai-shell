/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

public record AgentToolResult(boolean success, String message, JsonElement data) {
    public static AgentToolResult success(JsonElement data) {
        return new AgentToolResult(true, "ok", data == null ? JsonNull.INSTANCE : data);
    }

    public static AgentToolResult error(String message) {
        return new AgentToolResult(false, message, JsonNull.INSTANCE);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("success", success);
        json.addProperty("message", message);
        json.add("data", data == null ? JsonNull.INSTANCE : data);
        return json;
    }
}
