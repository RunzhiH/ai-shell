package com.runzhi.aishell.common.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
    void macOSPicksTheDmg() {
        UpdateChecker.UpdateInfo info = info(asset("AIShell-1.11.0.dmg"), asset("AIShell-1.11.0.msi"));
        Optional<UpdateChecker.ReleaseAsset> picked = AppUpdater.installableAsset(info, "macos");
        assertTrue(picked.isPresent());
        assertEquals("AIShell-1.11.0.dmg", picked.get().name());
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
        assertTrue(script.stream().anyMatch(line -> line.contains("AIShell.exe")), "安装成功后需重启应用");
        assertTrue(script.stream().anyMatch(line -> line.contains("chcp 65001")), "中文输出需切换控制台编码");
    }

    @Test
    void windowsScriptHandlesMissingExecutable() {
        List<String> script = AppUpdater.buildWindowsUpdateScript(7, Path.of("C:/tmp/x.msi"), null);
        assertTrue(script.stream().noneMatch(line -> line.contains("AIShell.exe")));
        assertTrue(script.stream().anyMatch(line -> line.contains("msiexec /i")));
    }
}
