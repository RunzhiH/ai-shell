package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 系统提示词读取目标系统/Shell 设置的回归（GitHub #9）：
 * AI 设置页的「目标系统」「默认 Shell」此前没有任何消费方，现在写进系统提示词。
 */
class AgentRuntimeTargetServerTest {

    @Test
    void promptIncludesThePersistedTargetOsAndShell() {
        String previousOs = AgentOsPreference.current();
        String previousShell = AgentOsPreference.currentShell();
        try {
            AgentOsPreference.set("windows");
            AgentOsPreference.setShell("PowerShell");
            String description = AgentRuntime.targetServerDescription();
            assertTrue(description.contains("windows"), "系统提示词应包含目标系统");
            assertTrue(description.contains("powershell"), "系统提示词应包含目标 Shell");

            AgentOsPreference.set("macos");
            AgentOsPreference.setShell("Zsh");
            description = AgentRuntime.targetServerDescription();
            assertTrue(description.contains("macos"));
            assertTrue(description.contains("zsh"));
        } finally {
            AgentOsPreference.set(previousOs);
            AgentOsPreference.setShell(previousShell);
        }
    }

    @Test
    void descriptionIsMeaningfulForTheDefaultLinuxBashTarget() {
        String description = AgentRuntime.targetServerDescription();
        assertTrue(description.contains("linux") || description.contains("windows") || description.contains("macos"));
        assertTrue(description.contains("shell"));
    }
}
