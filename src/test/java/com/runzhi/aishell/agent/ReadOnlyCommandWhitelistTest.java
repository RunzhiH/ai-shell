/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动批准白名单：只读查询与安全写入，危险/未识别命令一律回退人工。 */
class ReadOnlyCommandWhitelistTest {

    @Test
    void acceptsCommonInspectionCommands() {
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("ls -la /srv"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("df -h"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("LC_ALL=C df -h"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("systemctl status sshd"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("journalctl -u sshd --no-pager"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("date"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("date -u"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("hostname -f"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("hostname -I"));
        assertTrue(ReadOnlyCommandWhitelist.isReadOnly("cat /etc/os-release"));
    }

    @Test
    void rejectsShellWrappersAndMetacharacters() {
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("ls; rm -rf /srv"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("cat /srv/x | rm -rf /srv"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("echo hi > /srv/x"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("env rm -rf /srv"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("sudo ls"));
    }

    @Test
    void rejectsStateChangingFlags() {
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("date -s 2020-01-01"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("date --set=2020-01-01"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("hostname evil"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("hostname -F /tmp/hostname"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("journalctl --vacuum-time=1d"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("journalctl --rotate"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("systemctl restart sshd"));
    }

    @Test
    void rejectsFollowFlagsAndCredentialPaths() {
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("tail -f /var/log/syslog"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("cat /root/.ssh/id_rsa"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("cat /etc/shadow"));
        assertFalse(ReadOnlyCommandWhitelist.isReadOnly("ls /root/.ssh/"));
    }

    @Test
    void acceptsSafeWriteCommands() {
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("mkdir -p /srv/app/new"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("touch /srv/app/note"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("cp /srv/a /srv/b"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("mv /srv/a /srv/b"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("ln -s /srv/a /srv/b"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("install -m 644 /srv/a /srv/b"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("sed -i 's/a/b/' /srv/app/note"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("sed --in-place=.bak 's/a/b/' /srv/app/note"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("echo hello > /srv/app/note"));
        assertTrue(ReadOnlyCommandWhitelist.isWriteOperation("printf '%s' hi >> /srv/app/note"));
    }

    @Test
    void rejectsUnsafeWriteShapes() {
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("rm /srv/app/note"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("chmod 777 /srv/app/note"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("sudo cp /srv/a /srv/b"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("echo x > /etc/passwd"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("cp /srv/a /srv/b && rm /srv/app/c"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("cp /srv/a /srv/b | cat"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("cp /root/.ssh/id_rsa /tmp/key"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("sed -i 's/a/b/' /root/.ssh/config"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("sed 's/a/b/' /srv/app/note"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("echo hello > /root/.ssh/config"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("echo $(id) > /srv/app/note"));
        assertFalse(ReadOnlyCommandWhitelist.isWriteOperation("echo a > /srv/a > /srv/b"));
    }
}
