package com.runzhi.aishell.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupPageTest {
    @Test
    void parsesSupportedPagesAndFallsBackSafely() {
        assertEquals(StartupPage.TERMINAL, StartupPage.fromPreference("终端"));
        assertEquals(StartupPage.WELCOME, StartupPage.fromPreference("欢迎页"));
        assertEquals(StartupPage.CONNECTIONS, StartupPage.fromPreference("文件管理器"));
        assertEquals(StartupPage.CONNECTIONS, StartupPage.fromPreference(null));
    }
}
