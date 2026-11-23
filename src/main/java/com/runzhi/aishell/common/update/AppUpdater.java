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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
     * Windows 取 .msi（优先文件名带 AIShell 的），macOS 取 .dmg；
     * 便携 zip、源码包与其他平台一律不选。
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
            case "macos" -> ".dmg";
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
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(asset.url()))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "AIShell-Updater")
                .GET()
                .build();
        Path target = Files.createTempDirectory("aishell-update")
                .resolve(safeFileName(asset.name(), asset.url()));
        HttpResponse.BodyHandler<Path> handler = responseInfo -> {
            long contentLength = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            BodySubscriber<Path> fileSubscriber = HttpResponse.BodySubscribers.ofFile(target);
            return new ProgressSubscriber(fileSubscriber, contentLength, listener, target);
        };
        client.send(request, handler);
        if (Files.size(target) == 0) {
            throw new IOException("下载内容为空: " + asset.url());
        }
        log.info("更新包已下载: {} ({} 字节)", target, Files.size(target));
        return target;
    }

    /** URL 末段自带扩展名时优先使用，防止附件名缺失；去掉非法路径字符。 */
    private static String safeFileName(String name, String url) {
        String candidate = (name == null || name.isBlank()) ? url : name;
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0 && slash < candidate.length() - 1) {
            candidate = candidate.substring(slash + 1);
        }
        return candidate.replaceAll("[^A-Za-z0-9._-]", "_");
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
                Path script = writeWindowsScript(downloaded);
                new ProcessBuilder("cmd", "/c", "start", "", script.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
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
        lines.add("chcp 65001 >nul");
        lines.add("title AIShell 更新安装");
        lines.add("echo AIShell 更新已就绪，等待程序退出后开始安装...");
        lines.add(":wait");
        lines.add("tasklist /fi " + quote + "PID eq " + pid + quote + " 2>nul | find " + pid + " >nul");
        lines.add("if not errorlevel 1 (");
        lines.add("    timeout /t 1 /nobreak >nul");
        lines.add("    goto wait");
        lines.add(")");
        lines.add("echo 正在安装，请稍候（MSI 升级窗口可能一闪而过）...");
        lines.add("msiexec /i " + quote + msi.toAbsolutePath() + quote + " /qb");
        lines.add("if errorlevel 1 (");
        lines.add("    echo 安装失败（可能被取消），已打开安装包，请手动完成。");
        lines.add("    start " + quote + quote + " " + quote + msi.toAbsolutePath() + quote);
        lines.add("    timeout /t 20 >nul");
        lines.add("    exit /b 1");
        lines.add(")");
        lines.add("echo 安装完成，正在重新启动 AIShell。");
        lines.add("timeout /t 2 >nul");
        if (exe != null) {
            lines.add("start " + quote + quote + " " + quote + exe.toAbsolutePath() + quote);
        }
        return lines;
    }

    private static Path writeWindowsScript(Path msi) throws IOException {
        Path script = Files.createTempFile("aishell-update", ".cmd");
        List<String> lines = buildWindowsUpdateScript(ProcessHandle.current().pid(), msi, applicationExecutable());
        Files.write(script, lines, java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        return script;
    }

    /**
     * 当前应用程序的可执行文件路径：
     * 打包后 jar 位于 <app-image>/app/*.jar（macOS 为 AIShell.app/Contents/app/*.jar），
     * app-image 根目录即 exe 所在；IDE 直跑时返回 null（不支持自更新）。
     */
    static Path applicationExecutable() {
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
        private final Path target;
        private long downloaded;

        private ProgressSubscriber(BodySubscriber<Path> delegate, long total, ProgressListener listener, Path target) {
            this.delegate = delegate;
            this.total = total;
            this.listener = listener;
            this.target = target;
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
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // 清理失败不影响报错
            }
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
