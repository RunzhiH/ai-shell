package com.runzhi.aishell.common.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自更新逻辑回归：按平台挑选安装包、Windows 安装脚本生成。
 */
class AppUpdaterTest {

    private UpdateChecker.UpdateInfo info(UpdateChecker.ReleaseAsset... assets) {
        return new UpdateChecker.UpdateInfo("1.11.0", "https://github.com/RunzhiH/ai-shell/releases/tag/v1.11.0",
                "更新说明", List.of(assets));
    }

    private UpdateChecker.ReleaseAsset asset(String name) {
        return new UpdateChecker.ReleaseAsset(name, "https://example.com/" + name, 1024L);
    }

    @Test
    void windowsPicksTheMsiAndSkipsPortableZip() {
        UpdateChecker.UpdateInfo info = info(
                asset("AIShell-1.11.0.msi"),
                asset("AIShell-1.11.0-portable.zip"),
                asset("Source code (zip)"));
        Optional<UpdateChecker.ReleaseAsset> picked = AppUpdater.installableAsset(info, "Windows");
        assertTrue(picked.isPresent());
        assertEquals("AIShell-1.11.0.msi", picked.get().name());
    }

    @Test
    void macOSUsesTheReleasePage() {
        UpdateChecker.UpdateInfo info = info(asset("AIShell-1.11.0.dmg"), asset("AIShell-1.11.0.msi"));
        assertTrue(AppUpdater.installableAsset(info, "macos").isEmpty());
    }

    @Test
    void linuxAndEmptyAssetsAreNotInstallable() {
        UpdateChecker.UpdateInfo info = info(asset("AIShell-1.11.0.deb"));
        assertTrue(AppUpdater.installableAsset(info, "linux").isEmpty());
        assertTrue(AppUpdater.installableAsset(info, null).isEmpty());
        assertTrue(AppUpdater.installableAsset(new UpdateChecker.UpdateInfo("1", "u", "n", null), "windows").isEmpty());
        assertTrue(AppUpdater.installableAsset(new UpdateChecker.UpdateInfo("1", "u", "n", List.of()), "windows").isEmpty());
    }

    @Test
    void msiWithoutAppNameIsStillAcceptable() {
        UpdateChecker.UpdateInfo info = info(asset("installer-1.11.0.msi"));
        Optional<UpdateChecker.ReleaseAsset> picked = AppUpdater.installableAsset(info, "windows");
        assertTrue(picked.isPresent());
        assertEquals("installer-1.11.0.msi", picked.get().name());
    }

    @Test
    void zipAssetsAreNeverAutoInstalled() {
        UpdateChecker.UpdateInfo info = info(asset("AIShell-1.11.0.zip"));
        assertTrue(AppUpdater.installableAsset(info, "windows").isEmpty());
    }

    @Test
    void windowsScriptWaitsForThePidThenInstallsAndRelaunches() {
        List<String> script = AppUpdater.buildWindowsUpdateScript(4242,
                Path.of("C:/tmp/aishell-update/AIShell-1.12.0.msi"),
                Path.of("C:/Program Files/AIShell/AIShell.exe"));
        assertEquals("@echo off", script.get(0));
        assertTrue(script.stream().anyMatch(line -> line.contains("4242")), "脚本需按 PID 等待本进程退出");
        assertTrue(script.stream().anyMatch(line -> line.contains("tasklist")), "需用 tasklist 轮询进程");
        assertTrue(script.stream().anyMatch(line -> line.contains("msiexec /i")
                && line.contains("AIShell-1.12.0.msi")), "需以 msiexec 安装下载好的 MSI");
        assertTrue(script.stream().anyMatch(line -> line.contains("/norestart")));
        assertTrue(script.stream().anyMatch(line -> line.contains("3010")));
        assertTrue(script.stream().anyMatch(line -> line.contains("1641")));
        assertFalse(script.stream().anyMatch(line -> line.stripLeading().startsWith("start ") && line.contains(".msi")));
        assertTrue(script.stream().anyMatch(line -> line.contains("AIShell.exe")), "安装成功后需重启应用");
        assertTrue(script.stream().anyMatch(line -> line.contains("chcp 65001")), "中文输出需切换控制台编码");
    }

    @Test
    void windowsScriptHandlesMissingExecutable() {
        List<String> script = AppUpdater.buildWindowsUpdateScript(7, Path.of("C:/tmp/x.msi"), null);
        assertTrue(script.stream().noneMatch(line -> line.contains("AIShell.exe")));
        assertTrue(script.stream().anyMatch(line -> line.contains("msiexec /i")));
    }

    @Test
    void followsRedirectAndVerifiesTheCompleteAsset() throws Exception {
        byte[] installer = HexFormat.of().parseHex("d0cf11e0a1b11ae101234567");
        String digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(installer));
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var served = executor.submit(() -> {
                    respond(server, "302 Found", "Location: /download\r\n", new byte[0]);
                    respond(server, "200 OK", "", installer);
                    return null;
                });
                Path file = AppUpdater.download(localAsset(server, installer.length, digest), null);
                try {
                    assertArrayEquals(installer, Files.readAllBytes(file));
                    served.get();
                } finally {
                    AppUpdater.discardDownload(file);
                }
                assertFalse(Files.exists(file.getParent()));
            }
        }
    }

    @Test
    void rejectsHttpErrorsHtmlSizeMismatchAndBadDigest() throws Exception {
        byte[] installer = HexFormat.of().parseHex("d0cf11e0a1b11ae101234567");
        assertRejected("404 Not Found", installer, installer.length, null, "HTTP 404");
        byte[] html = "<html>Not a download</html>".getBytes(StandardCharsets.UTF_8);
        assertRejected("200 OK", html, html.length, null, "MSI");
        assertRejected("200 OK", installer, installer.length + 1, null, "大小");
        assertRejected("200 OK", installer, installer.length, "sha256:wrong", "SHA-256");
    }

    @Test
    void interruptsAnInProgressDownload() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(5000);
            CountDownLatch finish = new CountDownLatch(1);
            var served = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    readRequest(socket);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\n"
                            + "partial response").getBytes(StandardCharsets.US_ASCII));
                    finish.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            CountDownLatch receiving = new CountDownLatch(1);
            CompletableFuture<Throwable> result = new CompletableFuture<>();
            Thread downloader = Thread.startVirtualThread(() -> {
                try {
                    Path unexpected = AppUpdater.download(localAsset(server, 5000, null), (bytes, total) -> receiving.countDown());
                    AppUpdater.discardDownload(unexpected);
                    result.complete(new AssertionError("Interrupted download succeeded"));
                } catch (Exception failure) {
                    result.complete(failure);
                }
            });
            try {
                assertTrue(receiving.await(5, TimeUnit.SECONDS));
                downloader.interrupt();
                assertInstanceOf(InterruptedException.class, result.get(5, TimeUnit.SECONDS));
            } finally {
                downloader.interrupt();
                finish.countDown();
            }
            served.get();
        }
    }

    private void assertRejected(String status, byte[] body, int size, String digest, String expected) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(5000);
            var served = executor.submit(() -> {
                respond(server, status, "", body);
                return null;
            });
            IOException failure = assertThrows(IOException.class,
                    () -> AppUpdater.download(localAsset(server, size, digest), null));
            assertTrue(failure.getMessage().contains(expected), failure.getMessage());
            served.get();
        }
    }

    private static UpdateChecker.ReleaseAsset localAsset(ServerSocket server, int size, String digest) {
        return new UpdateChecker.ReleaseAsset("AIShell-test.msi", "http://127.0.0.1:" + server.getLocalPort() + "/asset",
                size, digest);
    }

    private static void respond(ServerSocket server, String status, String headers, byte[] body) throws IOException {
        try (var socket = server.accept()) {
            readRequest(socket);
            var output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + status + "\r\n" + headers + "Connection: close\r\nContent-Length: "
                    + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
        }
    }

    private static void readRequest(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        var reader = socket.getInputStream();
        int matches = 0;
        byte[] delimiter = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        for (int read; matches < delimiter.length && (read = reader.read()) != -1; ) {
            matches = read == delimiter[matches] ? matches + 1 : 0;
        }
    }
}
