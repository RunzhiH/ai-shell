/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Blocks an Agent worker thread until a human decides on a mutating tool call.
 * <p>
 * The decision is rendered through {@link AgentActivityBus#publishApproval}, i.e. the same
 * approval card used for command proposals, so a {@link CommandRisk#HIGH} request automatically
 * gets the workspace console's two-step confirmation. Waiting is interrupt-aware: cancelling
 * the Agent task while a decision is pending rethrows a cancellation and stops the loop.
 */
public final class AgentApprovalGate {
    /** Wall-clock budget for one human decision; expiry rejects the tool call. */
    private static final long DECISION_TIMEOUT_MINUTES = 10;

    private AgentApprovalGate() { }

    /**
     * @return true when the user approved, false when the user rejected or the decision timed out.
     * @throws java.util.concurrent.CancellationException when the waiting thread was interrupted
     *         (Agent task cancelled) before a decision arrived.
     */
    public static boolean request(String sessionId, CommandRisk risk, String summary) {
        if (risk == null) risk = CommandRisk.MEDIUM;
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        AgentActivityBus.publishApproval(sessionId, "[" + risk.label() + "] " + summary, approved -> {
            if (!decision.isDone()) decision.complete(Boolean.TRUE.equals(approved));
        });
        try {
            return decision.get(DECISION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException timeout) {
            AgentAuditLogger.getInstance().record("approval_timeout", sessionId, risk, summary, "timeout");
            return false;
        } catch (ExecutionException execution) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Agent cancelled while waiting for approval");
        }
    }
}
