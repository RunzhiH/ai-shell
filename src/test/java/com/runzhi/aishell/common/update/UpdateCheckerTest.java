package com.runzhi.aishell.common.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 更新检查器的版本比较逻辑（GitHub issue #3 的自动更新检查）。
 * 只覆盖纯逻辑：网络查询在测试环境不稳定，由手动入口兜底。
 */
class UpdateCheckerTest {

    @Test
    void newerVersionIsDetected() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "v1.2.0"));
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.2.1"));
        assertTrue(UpdateChecker.isNewer("1.9.9", "2.0.0"));
    }

    @Test
    void numericSegmentsCompareByValueNotLength() {
        assertTrue(UpdateChecker.isNewer("1.9.0", "1.10.0"));
        assertFalse(UpdateChecker.isNewer("1.10.0", "1.9.0"));
    }

    @Test
    void equalAndOlderVersionsAreNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"));
        assertFalse(UpdateChecker.isNewer("1.2.0", "v1.2.0"));
        assertFalse(UpdateChecker.isNewer("1.2.1", "1.2.0"));
        assertFalse(UpdateChecker.isNewer("2.0.0", "1.9.9"));
    }

    @Test
    void missingSegmentTreatedAsZero() {
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"));
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2"));
        assertTrue(UpdateChecker.isNewer("1.1", "1.2.0"));
    }

    @Test
    void malformedVersionsAreNeverNewer() {
        assertFalse(UpdateChecker.isNewer(null, "1.2.0"));
        assertFalse(UpdateChecker.isNewer("1.2.0", null));
        assertFalse(UpdateChecker.isNewer("", "1.2.0"));
        assertFalse(UpdateChecker.isNewer("dev", "1.2.0")); // IDE 直跑版本不应提示更新
    }

    @Test
    void normalizeStripsVPrefixAndWhitespace() {
        assertEquals("1.2.0", UpdateChecker.normalizeVersion("v1.2.0"));
        assertEquals("2.0.0", UpdateChecker.normalizeVersion("  V2.0.0  "));
        assertEquals("1.10.3", UpdateChecker.normalizeVersion("1.10.3"));
        assertEquals("", UpdateChecker.normalizeVersion(null));
        assertEquals("", UpdateChecker.normalizeVersion(""));
    }
}
