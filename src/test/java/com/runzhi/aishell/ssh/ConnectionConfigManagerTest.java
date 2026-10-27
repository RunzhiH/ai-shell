package com.runzhi.aishell.ssh;

import com.runzhi.aishell.ssh.domain.ConnectionParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionConfigManagerTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void saveOriginalUserHome() {
        originalUserHome = System.getProperty("user.home");
        // Point user.home to temp dir so ConnectionConfigManager uses a temp .aishell/connections.json
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreOriginalUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    private ConnectionParams makeConnection(String host, int port, String username) {
        ConnectionParams cp = new ConnectionParams();
        cp.setHost(host);
        cp.setPort(port);
        cp.setUsername(username);
        cp.setPlainPassword("test-pass");
        return cp;
    }

    @Test
    void loadFromEmptyConfigReturnsEmptyList() {
        ConnectionConfigManager manager = new ConnectionConfigManager();

        List<ConnectionParams> connections = manager.loadConnections();

        assertNotNull(connections);
        assertTrue(connections.isEmpty());
    }

    @Test
    void saveThenLoadRetrievesConfig() {
        ConnectionConfigManager manager = new ConnectionConfigManager();
        ConnectionParams cp = makeConnection("192.168.1.100", 22, "admin");

        assertTrue(manager.saveConnections(List.of(cp)));
        List<ConnectionParams> loaded = manager.loadConnections();

        assertEquals(1, loaded.size());
        assertEquals("192.168.1.100", loaded.get(0).getHost());
        assertEquals(22, loaded.get(0).getPort());
        assertEquals("admin", loaded.get(0).getUsername());
    }

    @Test
    void updateConnectionReplacesOriginal() {
        ConnectionConfigManager manager = new ConnectionConfigManager();
        ConnectionParams original = makeConnection("10.0.0.1", 22, "root");
        manager.saveConnections(new ArrayList<>(List.of(original)));

        ConnectionParams updated = makeConnection("10.0.0.1", 22, "root");
        updated.setPlainPassword("new-pass");

        boolean result = manager.updateConnection(original, updated);

        assertTrue(result);
        List<ConnectionParams> loaded = manager.loadConnections();
        assertEquals(1, loaded.size());
        assertEquals("new-pass", loaded.get(0).getPassword());
    }

    @Test
    void removeConnectionLeavesEmptyList() {
        ConnectionConfigManager manager = new ConnectionConfigManager();
        ConnectionParams cp = makeConnection("server.example.com", 2222, "deploy");
        manager.saveConnections(new ArrayList<>(List.of(cp)));

        boolean result = manager.removeConnection(cp);

        assertTrue(result);
        assertTrue(manager.loadConnections().isEmpty());
    }

    @Test
    void addDuplicateConnectionReturnsFalse() {
        ConnectionConfigManager manager = new ConnectionConfigManager();
        ConnectionParams cp = makeConnection("host", 22, "user");

        assertTrue(manager.addConnection(cp));
        assertFalse(manager.addConnection(cp), "duplicate connection should not be added");
    }

    @Test
    void findConnectionsByHostReturnsMatches() {
        ConnectionConfigManager manager = new ConnectionConfigManager();
        ConnectionParams cp1 = makeConnection("alpha.host", 22, "u1");
        ConnectionParams cp2 = makeConnection("beta.host", 22, "u2");
        ConnectionParams cp3 = makeConnection("alpha.host", 2222, "u3");
        manager.saveConnections(List.of(cp1, cp2, cp3));

        List<ConnectionParams> results = manager.findConnectionsByHost("alpha.host");

        assertEquals(2, results.size());
    }

    @Test
    void clearAllConnectionsRemovesEverything() {
        ConnectionConfigManager manager = new ConnectionConfigManager();
        manager.addConnection(makeConnection("a", 22, "u1"));
        manager.addConnection(makeConnection("b", 22, "u2"));

        assertTrue(manager.clearAllConnections());
        assertEquals(0, manager.getConnectionCount());
    }

    @Test
    void updateNonExistentConnectionReturnsFalse() {
        ConnectionConfigManager manager = new ConnectionConfigManager();
        ConnectionParams existing = makeConnection("host", 22, "user");
        manager.saveConnections(new ArrayList<>(List.of(existing)));

        ConnectionParams notFound = makeConnection("other", 22, "ghost");
        ConnectionParams replacement = makeConnection("new", 22, "new");

        assertFalse(manager.updateConnection(notFound, replacement));
    }

    @Test
    void removeNonExistentConnectionReturnsFalse() {
        ConnectionConfigManager manager = new ConnectionConfigManager();

        assertFalse(manager.removeConnection(makeConnection("nohost", 22, "nouser")));
    }
}
