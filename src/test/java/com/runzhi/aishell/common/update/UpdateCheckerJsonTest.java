package com.runzhi.aishell.common.update;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 更新响应解析回归（自更新 1.12.0）：离线解析 release JSON，含附件列表。
 */
class UpdateCheckerJsonTest {

    private static final String RELEASE_JSON = """
            {
              "tag_name": "v1.12.0",
              "html_url": "https://github.com/RunzhiH/ai-shell/releases/tag/v1.12.0",
              "body": "自更新与其它修复",
              "assets": [
                {"name": "AIShell-1.12.0.msi", "browser_download_url": "https://example.com/a.msi", "size": 123456},
                {"name": "AIShell-1.12.0.dmg", "browser_download_url": "https://example.com/a.dmg", "size": 987654321},
                {"name": "AIShell-1.12.0-portable.zip", "browser_download_url": "https://example.com/p.zip"}
              ]
            }
            """;

    @Test
    void parsesVersionUrlNotesAndAssets() {
        Optional<UpdateChecker.UpdateInfo> parsed = UpdateChecker.parseReleaseJson(RELEASE_JSON);
        assertTrue(parsed.isPresent());
        UpdateChecker.UpdateInfo info = parsed.get();
        assertEquals("1.12.0", info.latestVersion(), "tag_name 的 v 前缀应去掉");
        assertEquals("https://github.com/RunzhiH/ai-shell/releases/tag/v1.12.0", info.releaseUrl());
        assertEquals("自更新与其它修复", info.releaseNotes());
        assertEquals(3, info.assets().size());
        assertEquals("AIShell-1.12.0.msi", info.assets().get(0).name());
        assertEquals(123456L, info.assets().get(0).size());
        assertEquals("https://example.com/a.dmg", info.assets().get(1).url());
    }

    @Test
    void malformedOrTaglessJsonYieldsEmpty() {
        assertTrue(UpdateChecker.parseReleaseJson("not json").isEmpty());
        assertTrue(UpdateChecker.parseReleaseJson("{}").isEmpty(), "缺 tag_name 不得当作有效版本");
        assertTrue(UpdateChecker.parseReleaseJson("").isEmpty());
    }

    @Test
    void missingAssetsFieldIsTolerated() {
        Optional<UpdateChecker.UpdateInfo> parsed = UpdateChecker.parseReleaseJson(
                "{\"tag_name\":\"v2.0.0\",\"html_url\":\"https://x\"}");
        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().assets().isEmpty());
    }
}
