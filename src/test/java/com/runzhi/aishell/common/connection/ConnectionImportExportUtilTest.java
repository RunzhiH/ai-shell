package com.runzhi.aishell.common.connection;

import com.runzhi.aishell.ssh.domain.ConnectionParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionImportExportUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsInvalidImportedRecords() throws Exception {
        Path input = tempDir.resolve("connections.json");
        Files.writeString(input, """
                [
                  {"host":"valid.example","port":22,"username":"alice","password":""},
                  {"host":null,"port":22,"username":"bob"},
                  {"host":"bad.example","port":70000,"username":"bob"}
                ]
                """);

        List<ConnectionParams> imported = ConnectionImportExportUtil.importConnections(input.toString());

        assertEquals(1, imported.size());
        assertEquals("valid.example", imported.getFirst().getHost());
    }

    @Test
    void mergeHandlesNullableFieldsWithoutThrowing() {
        ConnectionParams existing = new ConnectionParams();
        existing.setPort(22);
        ConnectionParams imported = new ConnectionParams();
        imported.setPort(22);

        assertDoesNotThrow(() -> ConnectionImportExportUtil.mergeConnections(
                List.of(existing), List.of(imported)));
    }

    @Test
    void exportNeverIncludesCredentials() throws Exception {
        ConnectionParams connection = new ConnectionParams();
        connection.setHost("server.example");
        connection.setPort(22);
        connection.setUsername("alice");
        connection.setPassword("super-secret");
        Path output = tempDir.resolve("export.json");

        assertTrue(ConnectionImportExportUtil.exportConnections(List.of(connection), output.toString()));
        String json = Files.readString(output);
        assertFalse(json.contains("super-secret"));
        assertFalse(json.contains("\"password\""));
    }
}
