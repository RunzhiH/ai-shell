/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.runzhi.aishell.ai.AIAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 截断恢复、malformed JSON 重试与上下文压缩的行为验证。
 *
 * <p>对应完善方案 5.7 / 5.9：失败可恢复、长任务上下文不丢失当前目标。</p>
 */
class AgentRuntimeRecoveryTest {
    private static final AgentToolRegistry.ApprovalGate AUTO_APPROVE =
            (sessionId, risk, summary) -> true;

    @Test
    void elevatesBudgetAndRetriesTruncatedResponse() {
        BudgetAwareAdapter adapter = new BudgetAwareAdapter(4096,
                "Error: OpenAI output was truncated at the token limit (finish_reason=length, max_tokens=4096)",
                "{\"type\":\"final_answer\",\"reasoning_summary\":\"ok\",\"answer\":\"完成\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));
        List<String> progress = new ArrayList<>();

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "长任务", "", progress::add);

        assertEquals(AgentDecision.Type.COMPLETE, decision.type());
        assertEquals(2, adapter.calls, "截断后应用更大预算重试一次");
        assertEquals(8192, adapter.usedHint, "提升后的预算应传给适配器");
        assertTrue(progress.stream().anyMatch(item -> item.contains("更大预算")));
    }

    @Test
    void elevatedBudgetDoublesWithoutFixedCap() {
        // 8192 的固定上限已移除：已配置大预算的模型也应按比例翻倍
        BudgetAwareAdapter adapter = new BudgetAwareAdapter(12_000,
                "Error: OpenAI output was truncated at the token limit (finish_reason=length, max_tokens=12000)",
                "{\"type\":\"final_answer\",\"reasoning_summary\":\"ok\",\"answer\":\"done\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "长任务", "", null);

        assertEquals(AgentDecision.Type.COMPLETE, decision.type());
        assertEquals(24_000, adapter.usedHint, "提升后的预算应翻倍且不受固定上限约束");
    }

    @Test
    void doesNotElevateBudgetWhenAdapterReportsNoModelLimit() {
        BudgetAwareAdapter adapter = new BudgetAwareAdapter(-1,
                "Error: OpenAI output was truncated at the token limit (finish_reason=length, max_tokens=4096)");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.nextAction(adapter, "tab-1", "长任务", "", null));

        assertEquals(1, adapter.calls, "模型上限未知时不应重试");
        assertTrue(failure.getMessage().contains("token 上限截断"));
    }

    @Test
    void malformedJsonRetriesTwiceThenFailsWithClearGuidance() {
        BudgetAwareAdapter adapter = new BudgetAwareAdapter(4096,
                "not-json", "still-not-json", "again-not-json", "not-json");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.nextAction(adapter, "tab-1", "测试", "", null));

        assertEquals(3, adapter.calls, "首次之外最多再重试 2 次");
        assertTrue(failure.getMessage().contains("未按 JSON 回复"));
    }

    @Test
    void compressesTranscriptKeepingGoalAndRecentSegments() {
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));
        List<String> progress = new ArrayList<>();
        StringBuilder transcript = new StringBuilder("SYSTEM_PROMPT\n\nUSER_GOAL:\n保持当前目标");
        for (int index = 1; index <= 12; index++) {
            transcript.append("\n\nMODEL_MESSAGE:\n{\"type\":\"tool_call\",\"tool\":\"disk_usage\",\"arguments\":{}}")
                    .append("\n\nTOOL_RESULT:\n").append("y".repeat(20_000));
        }

        String compressed = runtime.compressTranscript(transcript.toString(), progress::add);

        assertTrue(compressed.startsWith("SYSTEM_PROMPT"), "系统提示与目标必须保留");
        assertTrue(compressed.contains("USER_GOAL"), "当前目标必须保留");
        assertTrue(compressed.contains("OMITTED_EARLIER_TURNS"), "被省略的早期交互应有摘要标记");
        assertTrue(compressed.contains("disk_usage"), "摘要应列出早期使用过的工具");
        assertEquals(6, compressed.split("MODEL_MESSAGE:").length - 1,
                "早期交互折叠为摘要后只保留最近 " + 6 + " 段交互");
        assertTrue(progress.stream().anyMatch(item -> item.contains("已压缩")));
    }

    @Test
    void keepsShortTranscriptsUnchanged() {
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));
        String transcript = "USER_GOAL:\n短任务\n\nMODEL_MESSAGE:\n{\"type\":\"final_answer\"}";
        assertEquals(transcript, runtime.compressTranscript(transcript, ignored -> { }));
    }

    @Test
    void compressesOversizedToolResultsInsteadOfFailing() {
        String huge = "{\"output\":\"" + "z".repeat(64_000) + "\"}";
        String compressed = AgentRuntime.compressToolResult(huge);
        assertTrue(compressed.length() < huge.length(), "超长工具结果应被压缩");
        assertTrue(compressed.contains("output compressed"), "应标注被省略的字符数");
        assertTrue(compressed.endsWith("z\"}") || compressed.contains("\"}"), "尾部内容应保留");
    }

    /** 感知输出预算的测试适配器：记录截断重试实际使用的 hint。 */
    private static final class BudgetAwareAdapter implements AIAdapter {
        private final ArrayDeque<String> responses = new ArrayDeque<>();
        private final int maxOutputTokens;
        private int calls;
        private int usedHint = -1;
        private String lastResponse = "";

        private BudgetAwareAdapter(int maxOutputTokens, String... responses) {
            this.maxOutputTokens = maxOutputTokens;
            this.responses.addAll(List.of(responses));
        }

        @Override public String sendQuery(String query) {
            calls++;
            String response = responses.isEmpty() ? lastResponse : (lastResponse = responses.removeFirst());
            usedHint = -1;
            return response;
        }

        @Override public String sendQuery(String query, int maxOutputTokensHint) {
            calls++;
            String response = responses.isEmpty() ? lastResponse : (lastResponse = responses.removeFirst());
            usedHint = maxOutputTokensHint;
            return response;
        }

        @Override public int maxOutputTokens() { return maxOutputTokens; }
        @Override public boolean isConfigured() { return true; }
        @Override public String getName() { return "budget-stub"; }
        @Override public String generateCommand(String naturalLanguage) { return null; }
        @Override public String explainCommand(String command) { return ""; }
        @Override public String getHelp(String topic) { return ""; }
    }

    private static final class StubSessionTool implements AgentSessionTool {
        @Override public boolean isConnected(String sessionId) { return true; }
        @Override public String workingDirectory(String sessionId) { return "/srv/app"; }
        @Override public List<com.runzhi.aishell.ssh.domain.FileInfo> listRemoteFiles(String sessionId, String path) { return List.of(); }
        @Override public String readRemoteTextFile(String sessionId, String path, int maxBytes) { return "text"; }
        @Override public String systemInfo(String sessionId) { return "Linux"; }
        @Override public String diskUsage(String sessionId) { return "disk"; }
        @Override public String memoryUsage(String sessionId) { return "memory"; }
        @Override public String processSnapshot(String sessionId) { return "processes"; }
        @Override public String networkListeners(String sessionId) { return "listeners"; }
        @Override public String executeCommand(String sessionId, String command) { return "output"; }
        @Override public void deleteRemotePath(String sessionId, String path, boolean directory) { }
    }
}
