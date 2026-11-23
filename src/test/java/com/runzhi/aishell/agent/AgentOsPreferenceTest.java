package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 目标操作系统偏好回归（GitHub #6 / #9）：技能过滤与 Agent 提示词共同读取的存储。
 */
class AgentOsPreferenceTest {

    @Test
    void unsupportedValuesFallBackToDefault() {
        AgentOsPreference.set("not-an-os");
        assertEquals("linux", AgentOsPreference.current());
    }

    @Test
    void roundTripsSupportedValues() {
        String previous = AgentOsPreference.current();
        try {
            AgentOsPreference.set("windows");
            assertEquals("windows", AgentOsPreference.current());
            AgentOsPreference.set("macOS");
            assertEquals("macos", AgentOsPreference.current());
            AgentOsPreference.set("all");
            assertEquals("all", AgentOsPreference.current());
        } finally {
            AgentOsPreference.set(previous);
        }
    }

    @Test
    void shellRoundTripsSupportedValuesAndFallsBackForUnknown() {
        String previousShell = AgentOsPreference.currentShell();
        try {
            AgentOsPreference.setShell("PowerShell");
            assertEquals("powershell", AgentOsPreference.currentShell());
            AgentOsPreference.setShell("CMD");
            assertEquals("cmd", AgentOsPreference.currentShell());
            AgentOsPreference.setShell("Zsh");
            assertEquals("zsh", AgentOsPreference.currentShell());
            AgentOsPreference.setShell("fish");
            assertEquals("bash", AgentOsPreference.currentShell(), "未支持的 Shell 回落 bash");
        } finally {
            AgentOsPreference.setShell(previousShell);
        }
    }
}
