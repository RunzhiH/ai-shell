/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级的 Agent 自动批准策略，开关由 Agent 工作区维护并持久化。
 * <p>
 * 开关打开后自动通过：
 * <ul>
 *   <li>低风险的只读命令；</li>
 *   <li>低/中风险的安全文件写入命令（mkdir/touch/cp/mv/ln/install/sed -i）；</li>
 *   <li>中风险的文件写入/建目录/重命名工具。</li>
 * </ul>
 * 高风险操作（删除、危险命令）、sudo/服务/进程/包管理、权限修改以及未识别的命令
 * 始终需要人工确认；本策略刻意不提供“全部放行”模式。
 */
public final class AgentAutoApprovePolicy {
    /** Tab 上的运行期缓存键；Preferences 由 Agent 工作区按连接持久化。 */
    public static final String TAB_PROPERTY = "agentAutoApproveLowRisk";

    /** 已开启自动批准的会话（sessionId 即终端 tab id）。 */
    private static final Set<String> ENABLED_SESSIONS = ConcurrentHashMap.newKeySet();

    /** 中风险但属于常规文件写入的 Agent 工具；删除工具是高危，永远不在此列。 */
    private static final Set<String> AUTO_WRITE_TOOLS = Set.of(
            "write_remote_text_file", "create_remote_directory", "rename_remote_path");

    private AgentAutoApprovePolicy() { }

    public static void setEnabled(String sessionId, boolean enabled) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (enabled) ENABLED_SESSIONS.add(sessionId);
        else ENABLED_SESSIONS.remove(sessionId);
    }

    public static boolean isEnabled(String sessionId) {
        return sessionId != null && ENABLED_SESSIONS.contains(sessionId);
    }

    /** 会话结束（标签关闭）时清理，避免旧会话的开关残留。 */
    public static void clear(String sessionId) {
        if (sessionId == null) return;
        ENABLED_SESSIONS.remove(sessionId);
    }

    /**
     * 命令提案 / execute_command 的自动批准判定。
     * 低风险只读命令，或低/中风险的安全写入命令才可以自动通过；
     * 高风险、sudo、rm、服务/进程/包管理、chmod/chown 等一律返回 {@code false}。
     */
    public static boolean canAutoApproveCommand(String sessionId, CommandRisk risk, String command) {
        if (!isEnabled(sessionId) || risk == null || risk == CommandRisk.HIGH) return false;
        // 防御性再校验：即使未来有调用方忘记先过沙箱，也不允许自动放行沙箱拒绝的命令。
        if (!AgentSandboxPolicy.validate(command).allowed()) return false;
        if (risk == CommandRisk.LOW && ReadOnlyCommandWhitelist.isReadOnly(command)) return true;
        return ReadOnlyCommandWhitelist.isWriteOperation(command);
    }

    /**
     * Agent 写文件/建目录/重命名工具的自动批准判定。
     * 这些工具的风险等级为中风险；高风险删除工具永远返回 {@code false}。
     */
    public static boolean canAutoApproveTool(String sessionId, String toolName, CommandRisk risk) {
        return isEnabled(sessionId) && risk != null && risk != CommandRisk.HIGH
                && AUTO_WRITE_TOOLS.contains(toolName);
    }
}
