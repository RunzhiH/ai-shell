package com.runzhi.aishell.common.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceLifecycleManagerTest {
    @Test
    void serviceHolderFieldsAreInitiallyNull() {
        ServiceLifecycleManager.ServiceHolder holder = new ServiceLifecycleManager.ServiceHolder();
        assertNull(holder.sshService);
        assertNull(holder.aiService);
        assertNull(holder.systemMonitor);
        assertNull(holder.terminalTabService);
        assertNull(holder.terminalCommandService);
        assertNull(holder.terminalConnectionService);
        assertNull(holder.terminalUIService);
        assertNull(holder.performanceMonitor);
        assertNull(holder.resourceOptimizer);
    }
}
