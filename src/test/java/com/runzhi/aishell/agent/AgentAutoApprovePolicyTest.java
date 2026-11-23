/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAutoApprovePolicyTest {
    private static final String SESSION = "tab-policy";

    @AfterEach
    void clear() {
        AgentAutoApprovePolicy.clear(SESSION);
    }

    @Test
    void readOnlyAndSafeWriteCommandsAreEligible() {
        AgentAutoApprovePolicy.setEnabled(SESSION, true);

        assertTrue(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.LOW, "df -h"));
        assertTrue(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.LOW, "systemctl status sshd"));
        assertTrue(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.LOW, "mkdir /srv/app/new"));
        assertTrue(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.LOW, "touch /srv/app/note"));
        assertTrue(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.MEDIUM, "cp --preserve /srv/a /srv/b"));
        assertTrue(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.MEDIUM, "sed -i 's/a/b/' /srv/app/note"));
    }

    @Test
    void destructivePrivilegedAndUnrecognisedCommandsStayManual() {
        AgentAutoApprovePolicy.setEnabled(SESSION, true);

        assertFalse(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.LOW, "chmod 777 /srv/app/note"),
                "permission changes are not auto-approved");
        assertFalse(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.MEDIUM, "rm /srv/app/old.txt"),
                "deletions stay manual");
        assertFalse(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.MEDIUM, "sudo ls"),
                "privilege escalation stays manual");
        assertFalse(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.MEDIUM, "systemctl restart sshd"),
                "service changes stay manual");
        assertFalse(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.HIGH, "rm -rf /srv/app"),
                "high-risk commands must never be auto-approved");
        assertFalse(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.HIGH, "cp /srv/a /srv/b"),
                "high risk is never bypassed even for write-shaped commands");
    }

    @Test
    void writeToolsAreAutoApprovedButDeleteIsNot() {
        AgentAutoApprovePolicy.setEnabled(SESSION, true);

        assertTrue(AgentAutoApprovePolicy.canAutoApproveTool(SESSION, "write_remote_text_file", CommandRisk.MEDIUM));
        assertTrue(AgentAutoApprovePolicy.canAutoApproveTool(SESSION, "create_remote_directory", CommandRisk.MEDIUM));
        assertTrue(AgentAutoApprovePolicy.canAutoApproveTool(SESSION, "rename_remote_path", CommandRisk.MEDIUM));
        assertFalse(AgentAutoApprovePolicy.canAutoApproveTool(SESSION, "delete_remote_path", CommandRisk.HIGH),
                "deleting remote paths is high risk and always manual");
        assertFalse(AgentAutoApprovePolicy.canAutoApproveTool(SESSION, "write_remote_text_file", CommandRisk.HIGH));
        assertFalse(AgentAutoApprovePolicy.canAutoApproveTool(SESSION, "execute_command", CommandRisk.MEDIUM));
    }

    @Test
    void switchOffAndSessionCleanupDisableApproval() {
        AgentAutoApprovePolicy.setEnabled(SESSION, true);
        assertTrue(AgentAutoApprovePolicy.isEnabled(SESSION));

        AgentAutoApprovePolicy.setEnabled(SESSION, false);
        assertFalse(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.LOW, "ls"));

        AgentAutoApprovePolicy.setEnabled(SESSION, true);
        AgentAutoApprovePolicy.clear(SESSION);
        assertFalse(AgentAutoApprovePolicy.canAutoApproveCommand(SESSION, CommandRisk.LOW, "ls"));
    }
}
