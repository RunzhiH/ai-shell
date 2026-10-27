package com.runzhi.aishell.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SSHJumpHostConfigTest {

    @Test
    void defaultDisabledConfigHasEmptyFields() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(false, "", 22, "", "", "");

        assertFalse(config.enabled());
        assertEquals("", config.host());
        assertEquals(22, config.port());
        assertEquals("", config.username());
        assertEquals("", config.password());
        assertEquals("", config.privateKeyPath());
    }

    @Test
    void enabledConfigWithPasswordIsValid() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(true, "jump.example.com", 2222, "admin", "secret", "");

        assertTrue(config.enabled());
        assertEquals("jump.example.com", config.host());
        assertEquals(2222, config.port());
        assertEquals("admin", config.username());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void enabledConfigWithPrivateKeyIsValid() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(true, "10.0.0.1", 22, "root", "", "/root/.ssh/id_rsa");

        assertDoesNotThrow(config::validate);
    }

    @Test
    void disabledConfigSkipsValidation() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(false, "", 0, "", "", "");

        assertDoesNotThrow(config::validate, "disabled config should not throw");
    }

    @Test
    void enabledConfigWithBlankHostThrows() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(true, "   ", 22, "user", "pass", "");

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void enabledConfigWithZeroPortThrows() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(true, "host", 0, "user", "pass", "");

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void enabledConfigWithPortAbove65535Throws() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(true, "host", 70000, "user", "pass", "");

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void enabledConfigWithNoCredentialThrows() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(true, "host", 22, "user", "", "");

        assertThrows(IllegalArgumentException.class, config::validate,
                "must require password or private key");
    }

    @Test
    void portBoundaryAt65535IsValid() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(true, "host", 65535, "user", "pass", "");

        assertDoesNotThrow(config::validate);
    }

    @Test
    void portBoundaryAt1IsValid() {
        SSHJumpHostConfig config = new SSHJumpHostConfig(true, "host", 1, "user", "pass", "");

        assertDoesNotThrow(config::validate);
    }
}
