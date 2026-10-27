package com.runzhi.aishell.common.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AIShellModuleManagerTest {
    @Test
    void moduleManagerIsSingleton() {
        AIShellModuleManager m1 = AIShellModuleManager.getInstance();
        AIShellModuleManager m2 = AIShellModuleManager.getInstance();
        assertSame(m1, m2);
    }

    @Test
    void moduleManagerHasNavigationManager() {
        AIShellModuleManager mgr = AIShellModuleManager.getInstance();
        assertNotNull(mgr.getNavigationManager());
    }

    @Test
    void moduleManagerHasServiceRegistry() {
        AIShellModuleManager mgr = AIShellModuleManager.getInstance();
        assertNotNull(mgr.getServiceRegistry());
    }

    @Test
    void healthStatusReportsAllServices() {
        AIShellModuleManager mgr = AIShellModuleManager.getInstance();
        String health = mgr.getHealthStatus();
        assertNotNull(health);
        assertTrue(health.contains("SSH Service"));
        assertTrue(health.contains("AI Service"));
        assertTrue(health.contains("System Monitor"));
    }

    @Test
    void refreshFileListForTabWithInvalidIdDoesNotThrow() {
        AIShellModuleManager mgr = AIShellModuleManager.getInstance();
        assertDoesNotThrow(() -> mgr.refreshFileListForTab("nonexistent-tab"));
    }
}
