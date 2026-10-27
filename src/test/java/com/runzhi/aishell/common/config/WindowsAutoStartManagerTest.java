package com.runzhi.aishell.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsAutoStartManagerTest {
    @Test
    void quotesWindowsLauncherArgumentsAndEscapesEnvironmentExpansion() {
        String launcher = WindowsAutoStartManager.buildLauncher(
                "C:\\Program Files\\Java\\javaw.exe", new String[]{"-jar", "D:\\AI Shell\\app.jar", "100%"});

        assertEquals("@echo off\r\nstart \"\" \"C:\\Program Files\\Java\\javaw.exe\" "
                + "\"-jar\" \"D:\\AI Shell\\app.jar\" \"100%%\"\r\n", launcher);
    }
}
