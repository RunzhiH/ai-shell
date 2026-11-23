/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.update;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.LongConsumer;

/**
 * 应用自更新：下载当前平台的发行安装包，退出当前进程后由独立脚本完成安装并重启。
 * <p>
 * 目前支持 Windows（MSI，经 msiexec 升级，UpgradeCode 固定，覆盖安装）；
 * 其余平台不自动安装，由调用方回退到打开发版页面。
 * <p>
 * 安装时机必须避开运行中的文件锁：生成 .cmd 脚本后以 detached 方式启动，
 * 脚本轮询本进程 PID 退出才执行 msiexec，成功后启动新版本可执行文件。
 */
@Slf4j
public final class AppUpdater {

    private AppUpdater() {
        // 工具类，禁止实例化
    }

    /** 进度回调：已下载字节数 / 总字节数（总数未知时为 -1）。 */
    public interface ProgressListener extends LongConsumer {
        /** 每次读到块时回调；total 为 -1 表示服务端未给出 Content-Length。 */
        void onProgress(long downloaded, long total);

        @Override
        default void accept(long downloaded) {
            onProgress(downloaded, -1L);
        }
    }

    /**
     * 按操作系统挑选可直接安装的发行附件。
     * <p>
     * Windows 取 .msi（优先文件名带 AIShell 的），其他平台打开发布页。
     *
     * @param info         更新检查结果
     * @param os           目标系统标识：windows / macos / linux（小写）
     */
    public static Optional<UpdateChecker.ReleaseAsset> installableAsset(UpdateChecker.UpdateInfo info, String os) {
        if (info == null || info.assets() == null) {
            return Optional.empty();
        }
        String extension = switch (os == null ? "" : os.toLowerCase(java.util.Locale.ROOT)) {
            case "windows" -> ".msi";
            default -> null;
        };
        if (extension == null) {
            return Optional.empty();
        }
        UpdateChecker.ReleaseAsset fallback = null;
        for (UpdateChecker.ReleaseAsset asset : info.assets()) {
            String name = asset.name() == null ? "" : asset.name().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("portable") || name.contains(".zip") || name.contains(".tar.gz")) {
                continue;   // 便携包与源码包不能原地升级
            }
            if (name.endsWith(extension)) {
                if (name.contains("aishell")) {
                    return Optional.of(asset);     // 优先匹配本应用安装包
                }
                fallback = asset;
            }
        }
        return Optional.ofNullable(fallback);
    }

    /**
     * 下载发行附件到临时目录，带进度回调。
     *
     * @return 下载完成的本地文件路径
     */
    public static Path download(UpdateChecker.ReleaseAsset asset, ProgressListener listener)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(asset.url()))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "AIShell-Updater")
                .GET()
                .build();
        Path target = Files.createTempDirectory("aishell-update")
                .resolve(safeFileName(asset.name(), asset.url()));
        HttpResponse.BodyHandler<Path> handler = responseInfo -> {
            if (responseInfo.statusCode() != 200) {
                return HttpResponse.BodySubscribers.replacing(null);
            }
            long contentLength = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            BodySubscriber<Path> fileSubscriber = HttpResponse.BodySubscribers.ofFile(target);
            return new ProgressSubscriber(fileSubscriber, contentLength, listener);
        };
        boolean verified = false;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            var response = client.send(request, handler);
            if (response.statusCode() != 200) throw new IOException("下载更新包失败，HTTP " + response.statusCode());
            verifyDownload(target, asset);
            verified = true;
            log.info("更新包已下载并验证: {}", target);
            return target;
        } finally {
            if (!verified) discardDownload(target);
        }
    }

    /** 校验附件大小、MSI 容器头和 GitHub 提供的 SHA-256。 */
    private static void verifyDownload(Path target, UpdateChecker.ReleaseAsset asset)
            throws IOException, InterruptedException {
        long size = Files.size(target);
        if (size == 0 || (asset.size() > 0 && size != asset.size())) {
            throw new IOException("安装包大小与发布信息不一致");
        }
        try (var stream = Files.newInputStream(target);
             var input = new DigestInputStream(stream, MessageDigest.getInstance("SHA-256"))) {
            String signature = HexFormat.of().formatHex(input.readNBytes(8));
            if (!"d0cf11e0a1b11ae1".equals(signature)) throw new IOException("下载内容不是有效的 MSI 容器");
            if (asset.digest() != null && !asset.digest().isBlank()) {
                byte[] buffer = new byte[64 * 1024];
                while (input.read(buffer) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                }
                String actual = "sha256:" + HexFormat.of().formatHex(input.getMessageDigest().digest());
                if (!actual.equalsIgnoreCase(asset.digest())) throw new IOException("安装包 SHA-256 校验失败");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("无法验证安装包 SHA-256", impossible);
        }
    }

    /** 清理下载方法创建的文件和空临时目录。 */
    public static void discardDownload(Path downloaded) {
        try {
            Files.deleteIfExists(downloaded);
            Files.deleteIfExists(downloaded.getParent());
        } catch (IOException failure) {
            log.debug("清理更新包失败: {}", failure.getMessage());
        }
    }

    /** URL 末段自带扩展名时优先使用，防止附件名缺失；去掉非法路径字符。 */
    private static String safeFileName(String name, String url) {
        String candidate = (name == null || name.isBlank()) ? url : name;
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0 && slash < candidate.length() - 1) {
            candidate = candidate.substring(slash + 1);
        }
        String safe = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.endsWith(".msi") && safe.length() > 4 ? safe : "AIShell-update.msi";
    }

    /**
     * 生成并 detached 启动「等退出 → 安装 → 重启」脚本，然后由调用方退出本进程。
     *
     * @param downloaded 已下载的安装包路径
     * @param os         目标系统
     * @return true 表示脚本已启动，调用方应立即退出应用
     */
    public static boolean installAfterExit(Path downloaded, String os) {
        try {
            String platform = os == null ? "" : os.toLowerCase(java.util.Locale.ROOT);
            if ("windows".equals(platform)) {
                Path executable = applicationExecutable();
                if (executable == null) return false;
                Path script = writeWindowsScript(downloaded, executable);
                Process process = new ProcessBuilder("cmd", "/d", "/v:off", "/c", script.getFileName().toString())
                        .directory(script.getParent().toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(script.resolveSibling("install-update.log").toFile())
                        .start();
                // 脚本正常启动后会等待当前 PID，提前退出表示启动失败。
                try {
                    if (process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) return false;
                } catch (InterruptedException interrupted) {
                    process.destroy();
                    Thread.currentThread().interrupt();
                    return false;
                }
                log.info("更新脚本已启动，本进程退出后安装: {}", script);
                return true;
            }
            log.info("当前平台({})暂不支持自动安装，已跳过", platform);
            return false;
        } catch (Exception e) {
            log.warn("启动更新脚本失败: {}", e.getMessage());
            return false;
        }
    }

    /** Windows 安装脚本：等本进程退出 → msiexec /qb 升级 → 启动新版本。 */
    static List<String> buildWindowsUpdateScript(long pid, Path msi, Path exe) {
        String quote = String.valueOf('"');   // cmd 引号，用字符字面量避免 Java 转义混杂
        List<String> lines = new ArrayList<>();
        lines.add("@echo off");
        lines.add("setlocal DisableDelayedExpansion");
        lines.add("chcp 65001 >nul");
        lines.add("title AIShell 更新安装");
        lines.add("echo AIShell 更新已就绪，等待程序退出后开始安装...");
        lines.add(":wait");
        lines.add("tasklist /fi " + quote + "PID eq " + pid + quote + " 2>nul | find " + pid + " >nul");
        lines.add("if not errorlevel 1 (");
        lines.add("    ping -n 2 127.0.0.1 >nul");
        lines.add("    goto wait");
        lines.add(")");
        lines.add("echo 正在安装，请稍候（MSI 升级窗口可能一闪而过）...");
        String installer = msi.toAbsolutePath().toString().replace("%", "%%");
        String installLog = installer + ".log";
        lines.add("msiexec /i " + quote + installer + quote + " /qb /norestart /L*v " + quote + installLog + quote);
        lines.add("set \"installResult=%errorlevel%\"");
        lines.add("if \"%installResult%\"==\"1641\" exit /b 0");
        lines.add("if \"%installResult%\"==\"3010\" (");
        lines.add("    echo 安装完成，请重启 Windows 以完成更新。");
        lines.add("    exit /b 0");
        lines.add(")");
        lines.add("if not \"%installResult%\"==\"0\" (");
        lines.add("    echo 安装未完成，返回码 %installResult%。日志：" + quote + installLog + quote);
        lines.add("    exit /b %installResult%");
        lines.add(")");
        lines.add("echo 安装完成，正在重新启动 AIShell。");
        if (exe != null) {
            lines.add("start " + quote + quote + " " + quote + exe.toAbsolutePath().toString().replace("%", "%%") + quote);
        }
        return lines;
    }

    private static Path writeWindowsScript(Path msi, Path executable) throws IOException {
        Path script = msi.resolveSibling("install-update.cmd");
        List<String> lines = buildWindowsUpdateScript(ProcessHandle.current().pid(), msi, executable);
        Files.write(script, lines, java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return script;
    }

    /**
     * 当前应用程序的可执行文件路径：
     * 打包后 jar 位于 <app-image>/app/*.jar（macOS 为 AIShell.app/Contents/app/*.jar），
     * app-image 根目录即 exe 所在；IDE 直跑时返回 null（不支持自更新）。
     */
    public static Path applicationExecutable() {
        try {
            String location = AppUpdater.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI().getSchemeSpecificPart();
            if (location == null || location.isBlank()) {
                return null;
            }
            Path jar = Paths.get(location);
            if (!location.endsWith(".jar") || jar.getParent() == null || jar.getParent().getParent() == null) {
                return null;
            }
            Path appDir = jar.getParent().getParent();
            if (!Files.isDirectory(appDir)) {
                return null;
            }
            String exe = com.runzhi.aishell.common.config.AppConstants.APP_NAME + ".exe";
            Path executable = appDir.resolve(exe);
            return Files.isRegularFile(executable) ? executable : null;
        } catch (Exception e) {
            log.debug("无法定位应用程序可执行文件: {}", e.getMessage());
            return null;
        }
    }

    /** 订阅文件写入的同时统计已下载字节数，向 UI 报告进度。 */
    private static final class ProgressSubscriber implements BodySubscriber<Path> {
        private final BodySubscriber<Path> delegate;
        private final long total;
        private final ProgressListener listener;
        private long downloaded;

        private ProgressSubscriber(BodySubscriber<Path> delegate, long total, ProgressListener listener) {
            this.delegate = delegate;
            this.total = total;
            this.listener = listener;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            delegate.onSubscribe(subscription);
        }

        @Override
        public CompletionStage<Path> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            long chunk = 0;
            for (ByteBuffer buffer : items) {
                chunk += buffer.remaining();
            }
            downloaded += chunk;
            if (listener != null) {
                try {
                    listener.onProgress(downloaded, total);
                } catch (Exception ignored) {
                    // 进度回调失败不得中断下载
                }
            }
            delegate.onNext(items);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
