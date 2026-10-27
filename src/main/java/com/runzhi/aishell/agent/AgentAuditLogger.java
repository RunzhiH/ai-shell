/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.regex.Pattern;

/** Append-only, secret-redacted audit trail for AI/Agent actions. */
@Slf4j
public final class AgentAuditLogger {
    private static final AgentAuditLogger INSTANCE = new AgentAuditLogger();

    /** key=value / key:value / keyword value 形态的凭据（password、token、api_key 等）。 */
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|passwd|pwd|token|api[_-]?key|secret|authorization|bearer|credential)"
                    + "(\\s*[=:]\\s*|\\s+)([^\\s;&|]+)");
    /** mysql -pSecret、curl --password secret 等「标志位直跟凭据」形态。 */
    private static final Pattern FLAG_SECRET = Pattern.compile(
            "(?i)((?:^|[\\s;&|])(?:-[a-zA-Z]*p|--password|--passwd))\\s*(\\S+)");
    /** curl -u user:pass、SMTP URL 等带内联凭据的 user:pass 形态。 */
    private static final Pattern USER_PASSWORD_PAIR = Pattern.compile(
            "(?i)(-u|--user)\\s+[^\\s:]+:[^\\s]+");
    /** 裸密钥前缀（OpenAI sk-、Anthropic sk-ant- 等）。 */
    private static final Pattern BARE_TOKEN = Pattern.compile(
            "(?i)\\b(?:sk-ant-|sk-|pk-|rk-|ghp_|gho_|xoxb-|AIza)[A-Za-z0-9._~+/=-]{6,}");
    /** Authorization 头中的 Bearer/Basic 值。 */
    private static final Pattern HEADER_TOKEN = Pattern.compile(
            "(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}");

    private final Path auditFile = Path.of(System.getProperty("user.home"), ".aishell", "logs", "agent-audit.jsonl");

    private AgentAuditLogger() {}
    public static AgentAuditLogger getInstance() { return INSTANCE; }

    public synchronized void record(String event, String tabId, CommandRisk risk,
                                    String command, String result) {
        try {
            Files.createDirectories(auditFile.getParent());
            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", Instant.now().toString());
            entry.addProperty("event", event);
            entry.addProperty("tabId", tabId == null ? "unknown" : tabId);
            entry.addProperty("risk", risk == null ? "UNKNOWN" : risk.name());
            entry.addProperty("command", redact(command));
            entry.addProperty("result", redact(result));
            Files.writeString(auditFile, entry + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("Unable to append Agent audit log: {}", e.getMessage());
        }
    }

    private String redact(String value) {
        if (value == null) return "";
        // 先处理 Authorization 头中的 Bearer/Basic 值：KEY_VALUE_SECRET 会把 "Authorization: Bearer xxx"
        // 中的 "Bearer" 当作 key 的值吃掉，导致真正的 token 残留在审计文件里（2026-10-23 审计 P0-1）。
        String redacted = HEADER_TOKEN.matcher(value).replaceAll("$1 <redacted>");
        redacted = KEY_VALUE_SECRET.matcher(redacted).replaceAll("$1$2<redacted>");
        redacted = FLAG_SECRET.matcher(redacted).replaceAll("$1<redacted>");
        redacted = USER_PASSWORD_PAIR.matcher(redacted).replaceAll("$1 <redacted>");
        return BARE_TOKEN.matcher(redacted).replaceAll("<redacted>");
    }
}
