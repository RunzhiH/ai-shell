package com.runzhi.aishell.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 稳定 tab id：进程内永不复用已关闭标签的序号（v6 阶段 2）。
 */
class TabIdentityTest {

    @Test
    void idsAreUniqueAcrossManyGenerations() {
        TabIdentity.resetForTest();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String id = TabIdentity.nextTabId();
            assertTrue(ids.add(id), "重复 id: " + id);
        }
    }

    @Test
    void closedTabNumberIsNeverReused() {
        TabIdentity.resetForTest();
        String first = TabIdentity.nextTabId();
        String second = TabIdentity.nextTabId();
        String third = TabIdentity.nextTabId();
        assertNotEquals(first, second);
        assertNotEquals(second, third);
        // 关闭第二个标签后再开：新 id 不能等于任何存量 id
        String reopened = TabIdentity.nextTabId();
        assertNotEquals(first, reopened);
        assertNotEquals(second, reopened);
        assertNotEquals(third, reopened);
    }

    @Test
    void prefixedIdsStayUniqueAndShareOneSequence() {
        TabIdentity.resetForTest();
        assertNotEquals(TabIdentity.nextId("ssh"), TabIdentity.nextId("ssh"));
        assertNotEquals(TabIdentity.nextId("local"), TabIdentity.nextId("ssh"));
        assertEquals("ssh-", TabIdentity.nextId("ssh").substring(0, 4));
        assertEquals("tab-", TabIdentity.nextId(null).substring(0, 4));
        assertEquals("tab-", TabIdentity.nextId("  ").substring(0, 4));
    }
}
