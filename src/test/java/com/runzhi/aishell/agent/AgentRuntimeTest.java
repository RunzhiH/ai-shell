package com.runzhi.aishell.agent;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ssh.domain.FileInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    /** Test gate that approves every mutating tool without blocking on the UI. */
    private static final AgentToolRegistry.ApprovalGate AUTO_APPROVE = (sessionId, risk, summary) -> true;
    @Test
    void executesBoundReadOnlyToolThenReturnsCommandProposal() {
        StubAdapter adapter = new StubAdapter(
                "{\"type\":\"tool_call\",\"tool\":\"working_directory\",\"arguments\":{}}",
                "{\"type\":\"final_command\",\"command\":\"pwd\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        String command = runtime.resolveCommand(adapter, "tab-1", "show current directory", null);

        assertEquals("pwd", command);
        assertEquals(2, adapter.calls);
        assertTrue(adapter.lastPrompt.contains("TOOL_RESULT"));
        assertFalse(adapter.lastPrompt.contains("password" + "=secret"));
    }

    @Test
    void rejectsConnectionCreationAndMalformedModelMessages() {
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));
        StubAdapter adapter = new StubAdapter(
                "{\"type\":\"tool_call\",\"tool\":\"connect\",\"arguments\":{\"host\":\"example.com\"}}",
                "not-json");

        assertThrows(IllegalStateException.class,
                () -> runtime.resolveCommand(adapter, "tab-1", "connect elsewhere", null));
    }

    @Test
    void reportsThinkAndCleanModelResponseWithoutExecutingThinkText() {
        StubAdapter adapter = new StubAdapter(
                "<think>先检查当前目录，再提出只读命令</think>\n"
                        + "{\"type\":\"final_command\",\"command\":\"pwd\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));
        List<String> progress = new ArrayList<>();

        String command = runtime.resolveCommand(adapter, "tab-1", "查看目录", progress::add);

        assertEquals("pwd", command);
        assertTrue(progress.stream().anyMatch(item -> item.startsWith("[AI_THINK]") && item.contains("先检查")));
        assertFalse(progress.stream().anyMatch(item -> item.startsWith("[AI_RESPONSE]")));
    }

    @Test
    void reportsReasoningSummaryWithoutDuplicatingStructuredFinalResponse() {
        StubAdapter adapter = new StubAdapter("{\"type\":\"final_answer\","
                + "\"reasoning_summary\":\"检查结果已经足够\",\"answer\":\"运行正常\"}",
                "{\"type\":\"tool_call\",\"tool\":\"connection_status\",\"arguments\":{}}",
                "{\"type\":\"final_answer\",\"reasoning_summary\":\"已验证\",\"answer\":\"运行正常\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));
        List<String> progress = new ArrayList<>();

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "检查服务", "", progress::add);

        assertEquals("运行正常", decision.content());
        assertTrue(progress.stream().anyMatch(item -> item.equals("[AI_THINK]检查结果已经足够")));
        assertFalse(progress.stream().anyMatch(item -> item.startsWith("[AI_RESPONSE]")));
        assertTrue(progress.stream().anyMatch(item -> item.contains("拒绝无证据")));
    }

    @Test
    void continuesFromExecutionContextAndCanCompleteWithoutAnotherCommand() {
        StubAdapter adapter = new StubAdapter(
                "{\"type\":\"final_answer\",\"answer\":\"服务已经恢复\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "修复服务",
                "COMMAND_RESULT_UNTRUSTED:\\nsuccess=true\\noutput=active", null);

        assertEquals(AgentDecision.Type.COMPLETE, decision.type());
        assertEquals("服务已经恢复", decision.content());
        assertTrue(adapter.lastPrompt.contains("PRIOR_TASK_CONTEXT"));
        assertTrue(adapter.lastPrompt.contains("output=active"));
    }

    @Test
    void allowsMoreThanFourReadOnlyToolRounds() {
        StubAdapter adapter = new StubAdapter(
                "{\"type\":\"tool_call\",\"tool\":\"working_directory\",\"arguments\":{}}",
                "{\"type\":\"tool_call\",\"tool\":\"disk_usage\",\"arguments\":{}}",
                "{\"type\":\"tool_call\",\"tool\":\"memory_usage\",\"arguments\":{}}",
                "{\"type\":\"tool_call\",\"tool\":\"process_snapshot\",\"arguments\":{}}",
                "{\"type\":\"tool_call\",\"tool\":\"network_listeners\",\"arguments\":{}}",
                "{\"type\":\"final_answer\",\"answer\":\"检查完成\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "全面检查", "", null);

        assertEquals(AgentDecision.Type.COMPLETE, decision.type());
        assertEquals(6, adapter.calls);
    }

    @Test
    void retriesEmptyResponsesAndRecovers() {
        StubAdapter adapter = new StubAdapter("",
                "{\"type\":\"final_answer\",\"reasoning_summary\":\"ok\",\"answer\":\"完成\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "测试", "", null);

        assertEquals(AgentDecision.Type.COMPLETE, decision.type());
        assertEquals(2, adapter.calls);
        assertTrue(adapter.lastPrompt.contains("previous response was empty"));
    }

    @Test
    void failsWithClearMessageAfterRepeatedEmptyResponses() {
        StubAdapter adapter = new StubAdapter("", "", "");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> runtime.nextAction(adapter, "tab-1", "测试", "", null));

        assertTrue(e.getMessage().contains("空内容"));
        assertEquals(3, adapter.calls);
    }

    @Test
    void surfacesAdapterErrorResponseVerbatim() {
        StubAdapter adapter = new StubAdapter("Error: API request failed with status 503");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> runtime.nextAction(adapter, "tab-1", "测试", "", null));

        assertTrue(e.getMessage().contains("503"));
        assertEquals(1, adapter.calls);
    }

    @Test
    void retriesMalformedJsonResponseWithTargetedPrompt() {
        StubAdapter adapter = new StubAdapter(
                "only reasoning text, no JSON",
                "{\"type\":\"final_answer\",\"reasoning_summary\":\"ok\",\"answer\":\"完成\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "测试", "", null);

        assertEquals(AgentDecision.Type.COMPLETE, decision.type());
        assertEquals(2, adapter.calls);
        assertTrue(adapter.lastPrompt.contains("not the required JSON"));
    }

    @Test
    void returnsAskDecisionWithOptions() {
        StubAdapter adapter = new StubAdapter("{\"type\":\"ask_user\","
                + "\"reasoning_summary\":\"需要确认环境\","
                + "\"question\":\"在哪个环境执行？\","
                + "\"options\":[\"生产环境\",\"测试环境\",\"本地环境\"]}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "部署服务", "", null);

        assertEquals(AgentDecision.Type.ASK, decision.type());
        assertEquals("在哪个环境执行？", decision.content());
        assertEquals(List.of("生产环境", "测试环境", "本地环境"), decision.options());
        assertTrue(decision.context().contains("MODEL_ASK"));
    }

    @Test
    void rejectsAskWithTooFewOptions() {
        StubAdapter adapter = new StubAdapter("{\"type\":\"ask_user\","
                + "\"question\":\"继续吗？\",\"options\":[\"继续\"]}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> runtime.nextAction(adapter, "tab-1", "测试", "", null));

        assertTrue(e.getMessage().contains("2 to 8"));
    }

    @Test
    void rejectsAskWithNonStringOption() {
        StubAdapter adapter = new StubAdapter("{\"type\":\"ask_user\","
                + "\"question\":\"选择数字？\",\"options\":[1, 2]}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> runtime.nextAction(adapter, "tab-1", "测试", "", null));

        assertTrue(e.getMessage().contains("strings only"));
    }

    @Test
    void coercesNonStringToolArgumentsToStrings() {
        StubSessionTool tool = new StubSessionTool();
        StubAdapter adapter = new StubAdapter(
                "{\"type\":\"tool_call\",\"tool\":\"delete_remote_path\","
                        + "\"arguments\":{\"path\":123,\"directory\":true}}",
                "{\"type\":\"final_answer\",\"reasoning_summary\":\"ok\",\"answer\":\"ok\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(tool, AUTO_APPROVE));

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "测试", "", null);

        assertEquals(AgentDecision.Type.COMPLETE, decision.type());
        assertEquals("123", tool.lastDeletePath);
        assertTrue(tool.lastDeleteDirectory);
    }

    @Test
    void joinsStringArrayToolArguments() {
        StubSessionTool tool = new StubSessionTool();
        StubAdapter adapter = new StubAdapter(
                "{\"type\":\"tool_call\",\"tool\":\"execute_command\","
                        + "\"arguments\":{\"command\":[\"ls\",\"-la\"]}}",
                "{\"type\":\"final_answer\",\"reasoning_summary\":\"ok\",\"answer\":\"ok\"}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(tool, AUTO_APPROVE));

        AgentDecision decision = runtime.nextAction(adapter, "tab-1", "测试", "", null);

        assertEquals(AgentDecision.Type.COMPLETE, decision.type());
        assertEquals("ls -la", tool.lastCommand);
    }

    @Test
    void stopsAfterTheConfiguredStepBudget() {
        // 模型始终只调用工具、从不给出最终答案：达到轮次预算必须停止
        StubAdapter adapter = new StubAdapter(
                "{\"type\":\"tool_call\",\"tool\":\"connection_status\",\"arguments\":{}}");
        AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(new StubSessionTool(), AUTO_APPROVE), 3);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> runtime.nextAction(adapter, "tab-1", "无限循环任务", "", null));

        assertTrue(e.getMessage().contains("最大轮次上限"));
        assertEquals(3, adapter.calls, "预算耗尽后不应再调用模型");
    }

    private static final class StubAdapter implements AIAdapter {
        private final ArrayDeque<String> responses = new ArrayDeque<>();
        private int calls;
        private String lastPrompt;
        private String lastResponse = "";

        private StubAdapter(String... responses) { this.responses.addAll(List.of(responses)); }
        @Override public String sendQuery(String query) {
            calls++;
            lastPrompt = query;
            // 用尽预设回复时重复最后一条，便于模拟「模型一直不收敛」的失控场景
            return responses.isEmpty() ? lastResponse : (lastResponse = responses.removeFirst());
        }
        @Override public boolean isConfigured() { return true; }
        @Override public String getName() { return "stub"; }
        @Override public String generateCommand(String naturalLanguage) { return null; }
        @Override public String explainCommand(String command) { return ""; }
        @Override public String getHelp(String topic) { return ""; }
    }

    private static final class StubSessionTool implements AgentSessionTool {
        String lastCommand;
        String lastDeletePath;
        boolean lastDeleteDirectory;

        @Override public boolean isConnected(String sessionId) { return true; }
        @Override public String workingDirectory(String sessionId) { return "/srv/app"; }
        @Override public List<FileInfo> listRemoteFiles(String sessionId, String path) { return List.of(); }
        @Override public String readRemoteTextFile(String sessionId, String path, int maxBytes) { return "text"; }
        @Override public String systemInfo(String sessionId) { return "Linux"; }
        @Override public String diskUsage(String sessionId) { return "disk"; }
        @Override public String memoryUsage(String sessionId) { return "memory"; }
        @Override public String processSnapshot(String sessionId) { return "processes"; }
        @Override public String networkListeners(String sessionId) { return "listeners"; }
        @Override public String executeCommand(String sessionId, String command) {
            lastCommand = command;
            return "output";
        }
        @Override public void deleteRemotePath(String sessionId, String path, boolean directory) {
            lastDeletePath = path;
            lastDeleteDirectory = directory;
        }
    }
}
