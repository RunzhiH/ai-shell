package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAuditLoggerTest {
    private static Path auditFile() {
        return Path.of(System.getProperty("user.home"), ".aishell", "logs", "agent-audit.jsonl");
    }

    /** 审计文件是 append-only，跨测试运行会累积旧条目；断言前先清空，避免历史数据干扰。 */
    private static void resetAuditFile() throws Exception {
        Files.deleteIfExists(auditFile());
    }

    @Test
    void createsAuditDirectoryAndRedactsSecrets() throws Exception {
        resetAuditFile();
        Path auditFile = auditFile();

        AgentAuditLogger.getInstance().record(
                "test", "tab-1", CommandRisk.LOW,
                "curl -H api_key=top-secret https://example.invalid", "ok");

        assertTrue(Files.isRegularFile(auditFile));
        String audit = Files.readString(auditFile);
        assertTrue(audit.contains("api_key=<redacted>"));
        assertFalse(audit.contains("top-secret"));
    }

    @Test
    void redactsValueShapedSecrets() throws Exception {
        resetAuditFile();
        Path auditFile = auditFile();

        AgentAuditLogger.getInstance().record("test", "tab-2", CommandRisk.MEDIUM,
                "mysql -h db -u root -pDbSecret -e 'select 1'", "ok");
        AgentAuditLogger.getInstance().record("test", "tab-2", CommandRisk.MEDIUM,
                "curl -u user:Pa55w0rd https://example.invalid", "ok");
        AgentAuditLogger.getInstance().record("test", "tab-2", CommandRisk.MEDIUM,
                "export API_KEY=sk-abcdEFGH0123456789xyz", "ok");
        AgentAuditLogger.getInstance().record("test", "tab-2", CommandRisk.MEDIUM,
                "curl -H 'Authorization: Bearer dGhpcyBpcyBhIHRva2Vu'", "ok");

        String audit = Files.readString(auditFile);
        assertFalse(audit.contains("DbSecret"));
        assertFalse(audit.contains("Pa55w0rd"));
        assertFalse(audit.contains("sk-abcdEFGH0123456789xyz"));
        assertFalse(audit.contains("dGhpcyBpcyBhIHRva2Vu"));
        assertTrue(audit.contains("<redacted>"));
    }
}
