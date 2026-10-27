/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.FileInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Explicit allow-list of credential-free session tools available to an AI agent:
 * read-only inspection tools plus SSH execution and SFTP file operations.
 * Implementations resolve an already-open application session from its opaque id;
 * callers never provide host, port, user name, password, or key material.
 * <p>
 * Every mutating tool ({@code execute_command}, write/delete/rename/mkdir) is gated behind a
 * mandatory human approval before it touches the remote host; read-only inspection tools run
 * directly. Approval outcomes are written to the audit trail.
 */
public final class AgentToolRegistry {
    private static final int MAX_EXEC_OUTPUT = 64 * 1024;
    private static final int MAX_TEXT_CONTENT = 64 * 1024;
    private static final int MAX_PATH_LENGTH = 4096;
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "connection_status", "working_directory", "list_remote_files", "read_remote_text_file", "system_info",
            "disk_usage", "memory_usage", "process_snapshot", "network_listeners");
    /** Tools that change remote state and therefore require an explicit human approval. */
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "execute_command", "write_remote_text_file", "create_remote_directory",
            "delete_remote_path", "rename_remote_path");
    private static final LinkedHashMap<String, String> TOOL_SIGNATURES = new LinkedHashMap<>();
    private static final Map<String, Set<String>> ALLOWED_ARGUMENTS = new LinkedHashMap<>();

    static {
        TOOL_SIGNATURES.put("execute_command", "command:string");
        TOOL_SIGNATURES.put("connection_status", "");
        TOOL_SIGNATURES.put("working_directory", "");
        TOOL_SIGNATURES.put("list_remote_files", "path:string");
        TOOL_SIGNATURES.put("read_remote_text_file", "path:string");
        TOOL_SIGNATURES.put("write_remote_text_file", "path:string, content:string");
        TOOL_SIGNATURES.put("create_remote_directory", "path:string");
        TOOL_SIGNATURES.put("delete_remote_path", "path:string, directory:boolean");
        TOOL_SIGNATURES.put("rename_remote_path", "from:string, to:string");
        TOOL_SIGNATURES.put("remote_path_exists", "path:string");
        TOOL_SIGNATURES.put("system_info", "");
        TOOL_SIGNATURES.put("disk_usage", "");
        TOOL_SIGNATURES.put("memory_usage", "");
        TOOL_SIGNATURES.put("process_snapshot", "");
        TOOL_SIGNATURES.put("network_listeners", "");

        ALLOWED_ARGUMENTS.put("execute_command", Set.of("command"));
        ALLOWED_ARGUMENTS.put("list_remote_files", Set.of("path"));
        ALLOWED_ARGUMENTS.put("read_remote_text_file", Set.of("path"));
        ALLOWED_ARGUMENTS.put("write_remote_text_file", Set.of("path", "content"));
        ALLOWED_ARGUMENTS.put("create_remote_directory", Set.of("path"));
        ALLOWED_ARGUMENTS.put("delete_remote_path", Set.of("path", "directory"));
        ALLOWED_ARGUMENTS.put("rename_remote_path", Set.of("from", "to"));
        ALLOWED_ARGUMENTS.put("remote_path_exists", Set.of("path"));
    }

    /** Supplies the human decision for a mutating tool call; blocks until decided. */
    @FunctionalInterface
    public interface ApprovalGate {
        boolean requestApproval(String sessionId, CommandRisk risk, String summary);
    }

    private final AgentSessionTool sessionTool;
    private final ApprovalGate approvalGate;

    public AgentToolRegistry(SSHService sshService) {
        this(new SshAgentSessionTool(sshService));
    }

    public AgentToolRegistry(AgentSessionTool sessionTool) {
        this(sessionTool, AgentApprovalGate::request);
    }

    public AgentToolRegistry(AgentSessionTool sessionTool, ApprovalGate approvalGate) {
        this.sessionTool = java.util.Objects.requireNonNull(sessionTool, "sessionTool");
        this.approvalGate = java.util.Objects.requireNonNull(approvalGate, "approvalGate");
    }

    public Set<String> supportedTools() { return Set.copyOf(TOOL_SIGNATURES.keySet()); }

    /** Human-readable "name arguments {spec}" list used in the model prompt. */
    public String toolDescriptions() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : TOOL_SIGNATURES.entrySet()) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(entry.getKey()).append(" arguments {").append(entry.getValue()).append('}');
        }
        return builder.toString();
    }

    public AgentToolResult execute(String toolName, String tabId, Map<String, String> arguments) {
        if (!TOOL_SIGNATURES.containsKey(toolName)) return AgentToolResult.error("Tool is not allowed: " + toolName);
        if (tabId == null || tabId.isBlank() || tabId.length() > 50) {
            return AgentToolResult.error("A valid target tabId is required");
        }
        String invalidArgument = validateArguments(toolName, arguments);
        if (invalidArgument != null) return AgentToolResult.error(invalidArgument);
        if (!sessionTool.isConnected(tabId)) {
            return AgentToolResult.error("Target tab is not connected");
        }
        // 沙箱校验先于审批：否则用户会对一条随后必被沙箱拒绝的命令做出批准决定（2026-10-23 审计 P0-2-10）
        if ("execute_command".equals(toolName)) {
            AgentSandboxPolicy.Verdict verdict = AgentSandboxPolicy.validate(requiredString(arguments, "command"));
            if (!verdict.allowed()) {
                return AgentToolResult.error("沙箱拒绝：" + verdict.reason());
            }
        }
        if (MUTATING_TOOLS.contains(toolName)) {
            CommandRisk risk = riskOf(toolName, arguments);
            String summary = describe(toolName, arguments);
            AgentToolResult rejected = awaitApproval(tabId, risk, summary);
            if (rejected != null) return rejected;
        }

        try {
            AgentToolResult result = switch (toolName) {
                case "execute_command" -> text("output", limit(
                        sessionTool.executeCommand(tabId, requiredString(arguments, "command")), MAX_EXEC_OUTPUT));
                case "connection_status" -> connectionStatus(tabId);
                case "working_directory" -> text("path", sessionTool.workingDirectory(tabId));
                case "list_remote_files" -> listFiles(tabId, requiredPath(arguments, "path"));
                case "read_remote_text_file" -> text("content",
                        sessionTool.readRemoteTextFile(tabId, protectedPath(arguments, "path", false), 64 * 1024));
                case "write_remote_text_file" -> writeTextFile(tabId, arguments);
                case "create_remote_directory" -> {
                    String path = protectedPath(arguments, "path", true);
                    sessionTool.createRemoteDirectory(tabId, path);
                    yield pathData(path, false);
                }
                case "delete_remote_path" -> {
                    String path = protectedPath(arguments, "path", true);
                    boolean directory = booleanArg(arguments, "directory");
                    sessionTool.deleteRemotePath(tabId, path, directory);
                    yield pathData(path, directory);
                }
                case "rename_remote_path" -> {
                    String from = protectedPath(arguments, "from", true);
                    String to = protectedPath(arguments, "to", true);
                    sessionTool.renameRemotePath(tabId, from, to);
                    JsonObject data = new JsonObject();
                    data.addProperty("from", from);
                    data.addProperty("to", to);
                    yield AgentToolResult.success(data);
                }
                case "remote_path_exists" -> existsData(tabId, requiredPath(arguments, "path"));
                case "system_info" -> text("output", sessionTool.systemInfo(tabId));
                case "disk_usage" -> text("output", sessionTool.diskUsage(tabId));
                case "memory_usage" -> text("output", sessionTool.memoryUsage(tabId));
                case "process_snapshot" -> text("output", sessionTool.processSnapshot(tabId));
                case "network_listeners" -> text("output", sessionTool.networkListeners(tabId));
                default -> AgentToolResult.error("Tool is not allowed: " + toolName);
            };
            recordAudit(toolName, tabId, arguments, result);
            return result;
        } catch (Exception e) {
            recordAudit(toolName, tabId, arguments, AgentToolResult.error(e.getMessage()));
            return AgentToolResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private AgentToolResult writeTextFile(String tabId, Map<String, String> arguments) throws Exception {
        String path = protectedPath(arguments, "path", true);
        String content = arguments.get("content");
        if (content == null || content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A valid content is required");
        }
        if (content.length() > MAX_TEXT_CONTENT) {
            throw new IllegalArgumentException("Text content exceeds the 64 KiB limit");
        }
        sessionTool.writeTextFile(tabId, path, content);
        JsonObject data = new JsonObject();
        data.addProperty("path", path);
        data.addProperty("bytes", content.length());
        return AgentToolResult.success(data);
    }

    private AgentToolResult connectionStatus(String tabId) {
        JsonObject data = new JsonObject();
        data.addProperty("sessionId", tabId);
        data.addProperty("connected", sessionTool.isConnected(tabId));
        return AgentToolResult.success(data);
    }

    private AgentToolResult listFiles(String tabId, String path) throws Exception {
        JsonArray data = new JsonArray();
        for (FileInfo file : sessionTool.listRemoteFiles(tabId, path)) {
            JsonObject item = new JsonObject();
            item.addProperty("name", file.getName());
            item.addProperty("size", file.getSize());
            item.addProperty("permission", file.getPermission());
            item.addProperty("modifiedTime", file.getModifiedTime());
            item.addProperty("directory", (file.getPermission() & 0040000) != 0);
            data.add(item);
        }
        return AgentToolResult.success(data);
    }

    private AgentToolResult text(String key, String value) {
        JsonObject data = new JsonObject();
        data.addProperty(key, value == null ? "" : value);
        return AgentToolResult.success(data);
    }

    private AgentToolResult pathData(String path, boolean directory) {
        JsonObject data = new JsonObject();
        data.addProperty("path", path);
        data.addProperty("directory", directory);
        return AgentToolResult.success(data);
    }

    private AgentToolResult existsData(String tabId, String path) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("path", path);
        data.addProperty("exists", sessionTool.remotePathExists(tabId, path));
        return AgentToolResult.success(data);
    }

    private String requiredPath(Map<String, String> arguments, String name) {
        String value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isBlank() || value.length() > MAX_PATH_LENGTH || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A valid remote " + name + " is required");
        }
        String normalized = value.replace('\\', '/');
        // 逐段检查 .. 与 .，覆盖 "/home/u/.."（无尾斜杠）这类绕过（2026-10-23 审计 P0-2-7）
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Path traversal is not allowed: " + name);
            }
        }
        if (normalized.contains("/./") || normalized.startsWith("./") || normalized.startsWith("/.")) {
            throw new IllegalArgumentException("Path traversal is not allowed: " + name);
        }
        return normalized;
    }

    /**
     * 写/删/重命名类 SFTP 工具禁止指向信任文件与系统目录；读类工具额外禁止读取私钥与凭据文件
     * （read_remote_text_file 无需审批，若放行会把私钥内容塞进 transcript 外泄）（2026-10-23 审计 P0-2-6）。
     */
    private String protectedPath(Map<String, String> arguments, String name, boolean forWrite) {
        String path = requiredPath(arguments, name);
        // 读操作只禁止凭据文件（读取后会把内容带进对话记录）；/etc/os-release 等系统信息属正常只读目标
        if (!forWrite && CREDENTIAL_FILE.matcher(path).find()) {
            throw new IllegalArgumentException("不允许读取凭据文件: " + name);
        }
        // 写/删/重命名禁止指向信任文件与系统目录
        if (forWrite && SENSITIVE_REMOTE_PATH.matcher(path).find()) {
            throw new IllegalArgumentException("不允许写入或删除信任文件/系统目录: " + name);
        }
        return path;
    }

    /** 私钥/凭据类文件：即便只读也禁止（Agent 通道读取后会把内容带进对话记录）。 */
    private static final java.util.regex.Pattern CREDENTIAL_FILE = java.util.regex.Pattern.compile(
            "(?i)(?:id_rsa|id_ecdsa|id_ed25519|id_dsa|\\.pem|\\.key|authorized_keys|known_hosts|/etc/shadow|/etc/sudoers|gnupg|\\.bash_history)\\b");

    /** 信任文件与系统目录：写/删/重命名一律禁止。 */
    private static final java.util.regex.Pattern SENSITIVE_REMOTE_PATH = java.util.regex.Pattern.compile(
            "(?i)(?:authorized_keys|known_hosts|sshd_config|/etc/|/\\.ssh/|\\.ssh/|/boot/|sshd_conf)\\b");

    private String requiredString(Map<String, String> arguments, String name) {
        String value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A valid " + name + " is required");
        }
        return value;
    }

    private boolean booleanArg(Map<String, String> arguments, String name) {
        String value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isBlank()) return false;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private String validateArguments(String toolName, Map<String, String> arguments) {
        Set<String> provided = arguments == null ? Set.of() : arguments.keySet();
        Set<String> allowed = ALLOWED_ARGUMENTS.getOrDefault(toolName, Set.of());
        for (String name : provided) {
            if (!allowed.contains(name)) return "Unexpected tool argument: " + name;
        }
        return null;
    }

    private void recordAudit(String toolName, String tabId, Map<String, String> arguments, AgentToolResult result) {
        Map<String, String> args = arguments == null ? Map.of() : arguments;
        String event = READ_ONLY_TOOLS.contains(toolName) ? "readonly_tool" : "agent_tool";
        String subject = switch (toolName) {
            case "execute_command" -> args.getOrDefault("command", "");
            case "write_remote_text_file" -> args.getOrDefault("path", "") + " (write)";
            case "create_remote_directory" -> args.getOrDefault("path", "") + " (mkdir)";
            case "delete_remote_path" -> args.getOrDefault("path", "") + " (delete"
                    + ("true".equalsIgnoreCase(args.get("directory")) ? " dir" : "") + ")";
            case "rename_remote_path" -> args.getOrDefault("from", "") + " -> " + args.getOrDefault("to", "");
            default -> toolName;
        };
        CommandRisk risk = riskOf(toolName, args);
        AgentAuditLogger.getInstance().record(event, tabId, risk, subject, result.success() ? "success" : "error");
    }

    /**
     * Blocks the Agent worker thread until the user decides on a mutating tool call.
     * @return null when approved, otherwise the error result to report back to the model.
     */
    private AgentToolResult awaitApproval(String tabId, CommandRisk risk, String summary) {
        AgentAuditLogger audit = AgentAuditLogger.getInstance();
        audit.record("tool_proposed", tabId, risk, summary, "pending");
        boolean approved = approvalGate.requestApproval(tabId, risk, summary);
        if (!approved) {
            audit.record("tool_rejected", tabId, risk, summary, "rejected");
            return AgentToolResult.error("用户拒绝执行：" + summary);
        }
        audit.record("tool_approved", tabId, risk, summary, "approved");
        return null;
    }

    /** Risk shown to the user and written to the audit trail for a mutating tool call. */
    private static CommandRisk riskOf(String toolName, Map<String, String> arguments) {
        return switch (toolName) {
            case "execute_command" -> CommandRiskAssessor.assess(arguments == null ? "" : arguments.getOrDefault("command", ""));
            case "delete_remote_path" -> CommandRisk.HIGH;
            case "write_remote_text_file", "create_remote_directory", "rename_remote_path" -> CommandRisk.MEDIUM;
            default -> CommandRisk.LOW;
        };
    }

    /** Human-readable summary of what the tool is about to change, shown in the approval card. */
    private static String describe(String toolName, Map<String, String> arguments) {
        Map<String, String> args = arguments == null ? Map.of() : arguments;
        return switch (toolName) {
            case "execute_command" -> "执行命令：" + args.getOrDefault("command", "");
            case "write_remote_text_file" -> {
                String content = args.get("content");
                yield "写入远程文件 " + args.getOrDefault("path", "") + "（"
                        + (content == null ? 0 : content.length()) + " 字节）";
            }
            case "create_remote_directory" -> "创建远程目录 " + args.getOrDefault("path", "");
            case "delete_remote_path" -> "删除远程路径（"
                    + ("true".equalsIgnoreCase(args.get("directory")) ? "目录" : "文件") + "）"
                    + args.getOrDefault("path", "");
            case "rename_remote_path" -> "重命名 " + args.getOrDefault("from", "") + " → " + args.getOrDefault("to", "");
            default -> toolName;
        };
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + "\n[output truncated]";
    }
}
