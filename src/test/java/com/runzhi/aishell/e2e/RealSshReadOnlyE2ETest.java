package com.runzhi.aishell.e2e;

import com.jcraft.jsch.*;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RealSshReadOnlyE2ETest {
    @Test
    void executesReadOnlyCommandAndListsSftpDirectory() throws Exception {
        String host = System.getenv("AISHELL_E2E_SSH_HOST");
        String user = System.getenv("AISHELL_E2E_SSH_USER");
        Assumptions.assumeTrue(host != null && user != null, "未配置真实 SSH E2E 环境变量");
        int port = Integer.parseInt(System.getenv().getOrDefault("AISHELL_E2E_SSH_PORT", "22"));
        String password = System.getenv("AISHELL_E2E_SSH_PASSWORD");
        String key = System.getenv("AISHELL_E2E_SSH_KEY");
        String keyPassphrase = System.getenv("AISHELL_E2E_SSH_KEY_PASSPHRASE");
        Assumptions.assumeTrue((password != null && !password.isBlank()) || (key != null && !key.isBlank()),
                "需要密码或私钥");

        JSch jsch = new JSch();
        if (key != null && !key.isBlank()) {
            if (keyPassphrase == null || keyPassphrase.isBlank()) {
                jsch.addIdentity(key);
            } else {
                jsch.addIdentity(key, keyPassphrase);
            }
        }
        String configuredKnownHosts = System.getenv("AISHELL_E2E_SSH_KNOWN_HOSTS");
        Path knownHosts = configuredKnownHosts == null || configuredKnownHosts.isBlank()
                ? Path.of(System.getProperty("user.home"), ".ssh", "known_hosts")
                : Path.of(configuredKnownHosts);
        Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(knownHosts), "known_hosts 中必须预先信任测试主机");
        jsch.setKnownHosts(knownHosts.toString());
        Session session = jsch.getSession(user, host, port);
        if (password != null) session.setPassword(password.getBytes(StandardCharsets.UTF_8));
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "yes");
        config.put("PreferredAuthentications", key == null ? "password" : "publickey,password");
        session.setConfig(config);
        try {
            session.connect(15_000);
            ChannelExec exec = (ChannelExec) session.openChannel("exec");
            exec.setCommand("printf AISHELL_E2E_OK\\n; pwd");
            InputStream output = exec.getInputStream();
            exec.connect(10_000);
            String text = new String(output.readAllBytes(), StandardCharsets.UTF_8);
            exec.disconnect();
            assertTrue(text.contains("AISHELL_E2E_OK"));

            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10_000);
            assertFalse(sftp.ls(sftp.pwd()).isEmpty());
            sftp.disconnect();
        } finally {
            session.disconnect();
        }
    }
}
