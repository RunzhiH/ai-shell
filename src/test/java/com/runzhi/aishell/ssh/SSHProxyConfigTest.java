package com.runzhi.aishell.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SSHProxyConfigTest {
    @Test void disabledProxyDoesNotRequireSession() {
        assertDoesNotThrow(() -> new SSHProxyConfig(false, "HTTP", "", 0, "", "").apply(null));
    }

    @Test void enabledProxyRejectsInvalidEndpointBeforeConnecting() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SSHProxyConfig(true, "HTTP", "", 70000, "", "").apply(null));
        assertTrue(error.getMessage().contains("无效"));
    }
}
