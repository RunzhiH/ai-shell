package com.runzhi.aishell.agent;

import com.runzhi.aishell.ssh.domain.FileInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolRegistryTest {
    /** Test gate that approves every mutating tool without blocking on the UI. */
    private static final AgentToolRegistry.ApprovalGate AUTO_APPROVE = (sessionId, risk, summary) -> true;

    @Test
    void exposesOnlyOpaqueSessionStateWithoutConnectionParameters() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute("connection_status", "tab-7", Map.of());

        assertTrue(result.success());
        assertEquals("tab-7", result.data().getAsJsonObject().get("sessionId").getAsString());
        assertTrue(result.data().getAsJsonObject().get("connected").getAsBoolean());
        assertFalse(result.data().getAsJsonObject().has("host"));
        assertFalse(result.data().getAsJsonObject().has("port"));
        assertFalse(result.data().getAsJsonObject().has("username"));
        assertFalse(result.data().getAsJsonObject().has("password"));
    }

    @Test
    void rejectsUnknownOrDisconnectedSessionsBeforeInvokingCapabilities() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(false), AUTO_APPROVE);

        assertFalse(registry.execute("working_directory", "tab-7", Map.of()).success());
        assertFalse(registry.execute("connect", "tab-7", Map.of("host", "example.com")).success());
    }

    @Test
    void rejectsConnectionParametersEvenForAllowedTools() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "connection_status", "tab-7", Map.of("host", "example.com", "password", "secret"));

        assertFalse(result.success());
        assertTrue(result.message().startsWith("Unexpected tool argument:"));
    }

    @Test
    void readsRemoteTextThroughBoundSessionCapability() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "read_remote_text_file", "tab-7", Map.of("path", "/etc/os-release"));

        assertTrue(result.success());
        assertEquals("text", result.data().getAsJsonObject().get("content").getAsString());
    }

    @Test
    void executesCommandThroughBoundSessionCapability() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "execute_command", "tab-7", Map.of("command", "df -h"));

        assertTrue(result.success());
        assertEquals("command-output:df -h", result.data().getAsJsonObject().get("output").getAsString());
    }

    @Test
    void rejectsCommandWithUnexpectedArguments() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "execute_command", "tab-7", Map.of("command", "df -h", "path", "/tmp"));

        assertFalse(result.success());
        assertTrue(result.message().startsWith("Unexpected tool argument:"));
    }

    @Test
    void writesTextFileThroughBoundSessionCapability() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "write_remote_text_file", "tab-7",
                Map.of("path", "/srv/app/note.txt", "content", "hello world"));

        assertTrue(result.success());
        assertEquals("/srv/app/note.txt", result.data().getAsJsonObject().get("path").getAsString());
        assertEquals(11, result.data().getAsJsonObject().get("bytes").getAsInt());
    }

    @Test
    void rejectsWriteWithOversizedContent() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "write_remote_text_file", "tab-7",
                Map.of("path", "/srv/app/big.txt", "content", "x".repeat(64 * 1024 + 1)));

        assertFalse(result.success());
        assertTrue(result.message().contains("64 KiB"));
    }

    @Test
    void deletesPathAndParsesDirectoryFlag() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "delete_remote_path", "tab-7", Map.of("path", "/srv/app/old", "directory", "true"));

        assertTrue(result.success());
        assertTrue(result.data().getAsJsonObject().get("directory").getAsBoolean());
    }

    @Test
    void rejectsDeleteWithInvalidBooleanFlag() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "delete_remote_path", "tab-7", Map.of("path", "/srv/app/old", "directory", "maybe"));

        assertFalse(result.success());
        assertTrue(result.message().contains("true or false"));
    }

    @Test
    void renamesRemotePathThroughBoundSessionCapability() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult result = registry.execute(
                "rename_remote_path", "tab-7", Map.of("from", "/a.txt", "to", "/b.txt"));

        assertTrue(result.success());
        assertEquals("/a.txt", result.data().getAsJsonObject().get("from").getAsString());
        assertEquals("/b.txt", result.data().getAsJsonObject().get("to").getAsString());
    }

    @Test
    void reportsRemotePathExistence() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        AgentToolResult exists = registry.execute("remote_path_exists", "tab-7", Map.of("path", "/exists/x"));
        AgentToolResult missing = registry.execute("remote_path_exists", "tab-7", Map.of("path", "/missing/x"));

        assertTrue(exists.success());
        assertTrue(exists.data().getAsJsonObject().get("exists").getAsBoolean());
        assertTrue(missing.success());
        assertFalse(missing.data().getAsJsonObject().get("exists").getAsBoolean());
    }

    @Test
    void toolDescriptionsListEverySupportedTool() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        String descriptions = registry.toolDescriptions();

        assertTrue(descriptions.contains("execute_command arguments {command:string}"));
        assertTrue(descriptions.contains("write_remote_text_file arguments {path:string, content:string}"));
        assertTrue(descriptions.contains("delete_remote_path arguments {path:string, directory:boolean}"));
        assertTrue(descriptions.contains("rename_remote_path arguments {from:string, to:string}"));
        assertTrue(descriptions.contains("remote_path_exists arguments {path:string}"));
        assertTrue(descriptions.contains("create_remote_directory arguments {path:string}"));
        for (String tool : registry.supportedTools()) {
            assertTrue(descriptions.contains(tool + " arguments"), "missing description for " + tool);
        }
    }

    @Test
    void rejectsPathTraversalInFileOperations() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        assertFalse(registry.execute("read_remote_text_file", "tab-7",
                Map.of("path", "../../etc/passwd")).success());
        assertFalse(registry.execute("write_remote_text_file", "tab-7",
                Map.of("path", "../secret.txt", "content", "data")).success());
        assertFalse(registry.execute("list_remote_files", "tab-7",
                Map.of("path", "/srv/../../../etc")).success());
        assertTrue(registry.execute("read_remote_text_file", "tab-7",
                Map.of("path", "/srv/app/config.json")).success());
    }

    @Test
    void rejectsSensitivePathsForCredentialReadsAndConfigWrites() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), AUTO_APPROVE);

        // 读取凭据文件必须拒绝（read_remote_text_file 无需审批，否则会把私钥带进对话记录）
        assertFalse(registry.execute("read_remote_text_file", "tab-7",
                Map.of("path", "/root/.ssh/id_rsa")).success());
        assertFalse(registry.execute("read_remote_text_file", "tab-7",
                Map.of("path", "/root/.ssh/authorized_keys")).success());
        assertFalse(registry.execute("read_remote_text_file", "tab-7",
                Map.of("path", "/etc/shadow")).success());
        // 写入/删除信任文件与系统目录必须拒绝（即便用户在审批卡上点同意）
        assertFalse(registry.execute("write_remote_text_file", "tab-7",
                Map.of("path", "/home/u/.ssh/authorized_keys", "content", "x")).success());
        assertFalse(registry.execute("delete_remote_path", "tab-7",
                Map.of("path", "/etc/ssh", "directory", "true")).success());
        assertFalse(registry.execute("rename_remote_path", "tab-7",
                Map.of("from", "/srv/app/a.txt", "to", "/srv/app/.ssh/authorized_keys")).success());
        // 合法的读写不受影响
        assertTrue(registry.execute("read_remote_text_file", "tab-7",
                Map.of("path", "/srv/app/config.json")).success());
        assertTrue(registry.execute("write_remote_text_file", "tab-7",
                Map.of("path", "/srv/app/note.txt", "content", "ok")).success());
    }

    @Test
    void mutatingToolsRequireApprovalBeforeExecution() {
        StringBuilder gateLog = new StringBuilder();
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), (sessionId, risk, summary) -> {
            gateLog.append(sessionId).append(':').append(risk.name()).append(':').append(summary).append(';');
            return true;
        });

        AgentToolResult result = registry.execute("write_remote_text_file", "tab-7",
                Map.of("path", "/srv/app/note.txt", "content", "hello world"));

        assertTrue(result.success());
        String log = gateLog.toString();
        assertTrue(log.contains("tab-7"), "gate must receive the session id");
        assertTrue(log.contains(CommandRisk.MEDIUM.name()), "write must be proposed as medium risk");
        assertTrue(log.contains("/srv/app/note.txt"), "gate summary must name the target path");
    }

    @Test
    void rejectedMutatingToolReportsErrorWithoutExecuting() {
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true),
                (sessionId, risk, summary) -> false);

        AgentToolResult result = registry.execute("delete_remote_path", "tab-7",
                Map.of("path", "/srv/app/old", "directory", "true"));

        assertFalse(result.success());
        assertTrue(result.message().contains("拒绝"), "rejection must be reported back to the model");
    }

    @Test
    void readOnlyToolsSkipTheApprovalGate() {
        Set<String> gated = new HashSet<>();
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), (sessionId, risk, summary) -> {
            gated.add(summary);
            return true;
        });

        registry.execute("list_remote_files", "tab-7", Map.of("path", "/srv/app"));
        registry.execute("read_remote_text_file", "tab-7", Map.of("path", "/srv/app/config.json"));
        registry.execute("connection_status", "tab-7", Map.of());

        assertTrue(gated.isEmpty(), "read-only tools must not ask for approval");
    }

    @Test
    void executeCommandRiskIsAssessedForTheApprovalCard() {
        List<CommandRisk> seen = new ArrayList<>();
        AgentToolRegistry registry = new AgentToolRegistry(new FakeSessionTool(true), (sessionId, risk, summary) -> {
            seen.add(risk);
            return true;
        });

        registry.execute("execute_command", "tab-7", Map.of("command", "rm -rf /srv/old"));

        assertEquals(List.of(CommandRisk.HIGH), seen, "rm -rf must be proposed as high risk");
    }

    private record FakeSessionTool(boolean connected) implements AgentSessionTool {
        @Override public boolean isConnected(String sessionId) { return connected; }
        @Override public String workingDirectory(String sessionId) { return "/srv/app"; }
        @Override public List<FileInfo> listRemoteFiles(String sessionId, String path) { return List.of(); }
        @Override public String readRemoteTextFile(String sessionId, String path, int maxBytes) { return "text"; }
        @Override public String systemInfo(String sessionId) { return "Linux"; }
        @Override public String executeCommand(String sessionId, String command) { return "command-output:" + command; }
        @Override public String writeTextFile(String sessionId, String path, String content) { return content; }
        @Override public void createRemoteDirectory(String sessionId, String path) { }
        @Override public void deleteRemotePath(String sessionId, String path, boolean directory) { }
        @Override public void renameRemotePath(String sessionId, String from, String to) { }
        @Override public boolean remotePathExists(String sessionId, String path) { return path.startsWith("/exists"); }
    }
}
