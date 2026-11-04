/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 按 sessionId（即终端 tab id）追踪 Agent 的运行与待审批状态。
 *
 * <p>状态归属的三条规则（v6 阶段 2 复审修正）：
 * <ol>
 * <li>待审批计数按 proposalId 登记：同一提议重放（历史卡片再次点击）不能扣减别的提议的计数；
 * <li>任务终结（SUCCESS / ERROR / CANCELLED）使其全部未决提议失效并清零计数 ——
 *     审批门超时或被中断时不会经过 UI 决策路径，否则计数会永远挂着；
 * <li>停止只作用于明确的目标会话，不做全局扫描。
 * </ol>
 * 驱动点是 {@link AgentWorkspaceController#receive}（已裹在 Platform.runLater 里），
 * 因此所有状态变更都在 FX 线程上发生，监听器可直接更新 UI。
 */
public final class AgentSessionTracker {

    /** 单个会话的状态快照，监听器据此更新 UI。 */
    public record SessionState(String sessionId, boolean running, int pendingApprovals) { }

    private static final AgentSessionTracker INSTANCE = new AgentSessionTracker();

    /** 单条提议的终结状态：只有首次有效终结才能核销计数。 */
    private static final class ProposalState {
        final AtomicBoolean decided = new AtomicBoolean(false);
        final AtomicBoolean registered = new AtomicBoolean(false);
    }

    private static final class SessionStateImpl {
        volatile boolean running;
        final AtomicInteger pending = new AtomicInteger(0);
        final Map<String, ProposalState> proposals = new ConcurrentHashMap<>();
    }

    private final Map<String, SessionStateImpl> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<SessionState>> listeners = new CopyOnWriteArrayList<>();

    private AgentSessionTracker() {
    }

    public static AgentSessionTracker getInstance() {
        return INSTANCE;
    }

    /**
     * 记录一条总线事件带来的状态变化。需要在收到事件的同一处调用（事件未被显示过滤前），
     * 保证目标标签没被打开时状态也不丢失。
     */
    public void record(AgentActivityBus.Event event) {
        if (event == null || event.sessionId() == null) return;
        String sessionId = event.sessionId();
        SessionStateImpl state = sessions.computeIfAbsent(sessionId, ignored -> new SessionStateImpl());
        switch (event.kind()) {
            case PROPOSAL -> {
                // 仅带审批句柄的提议才占用人工决策；自动批准的不占。
                if (event.approvalHandler() == null) break;
                String proposalId = event.proposalId();
                if (proposalId == null) {
                    state.pending.incrementAndGet();
                    notify(sessionId);
                    break;
                }
                ProposalState proposal = state.proposals.computeIfAbsent(proposalId, ignored -> new ProposalState());
                // 已终结的提议不应重新计入待审批（重放的历史事件）
                if (!proposal.decided.get() && proposal.registered.compareAndSet(false,true)) {
                    state.pending.incrementAndGet();
                    notify(sessionId);
                }
            }
            case STATUS -> {
                if(event.proposal()!=null && event.proposal().state()!=AgentProposal.State.PENDING
                        && acquireDecision(sessionId,event.proposalId())) resolve(sessionId);
            }
            case EXECUTION -> {
                if (!state.running) {
                    state.running = true;
                    notify(sessionId);
                }
            }
            case SUCCESS, ERROR, CANCELLED -> terminate(sessionId, state);
            default -> { }
        }
    }

    /**
     * 任务终结：停止运行标记，并把该会话全部未决提议标记为已终结、待审批清零。
     * 审批门超时/被中断时不会经过 UI 决策路径，若不在这里核销，计数会永久挂着，
     * 导致「停止该任务后再关闭」的提示无法被满足。
     */
    private void terminate(String sessionId, SessionStateImpl state) {
        boolean wasRunning = state.running;
        state.running = false;
        for (ProposalState proposal : state.proposals.values()) {
            proposal.decided.set(true);
        }
        int pendingBefore = state.pending.get();
        state.pending.set(0);
        if (wasRunning || pendingBefore != 0) {
            notify(sessionId);
        }
    }

    /**
     * 包裹审批句柄：仅当该提议尚未被终结时，才回调原句柄并核销待审批计数。
     * 历史已处理卡片再次点击会被直接忽略 —— 既不重复提交业务决策，也不扣减新提议的计数。
     *
     * @param proposalId 提议身份；null 表示旧式事件，保持「每次调用都核销」的旧行为
     */
    public Consumer<Boolean> trackDecision(String sessionId, String proposalId, Consumer<Boolean> handler) {
        return approved -> {
            if (!acquireDecision(sessionId, proposalId)) return;
            try {
                if (handler != null) handler.accept(approved);
            } finally {
                resolve(sessionId);
            }
        };
    }

    /** 首次终结该提议；已终结则返回 false。 */
    private boolean acquireDecision(String sessionId, String proposalId) {
        if (proposalId == null) return true;
        SessionStateImpl state = sessions.get(sessionId);
        if (state == null) return true;
        ProposalState proposal = state.proposals.get(proposalId);
        return proposal == null || proposal.decided.compareAndSet(false, true);
    }

    private void resolve(String sessionId) {
        SessionStateImpl state = sessions.get(sessionId);
        if (state == null) return;
        if (state.pending.decrementAndGet() < 0) {
            state.pending.set(0);
        }
        notify(sessionId);
    }

    /** 标签关闭或会话彻底结束时清除其状态。 */
    public void clear(String sessionId) {
        if (sessionId == null) return;
        SessionStateImpl removed = sessions.remove(sessionId);
        if (removed != null) {
            if (removed.pending.get() > 0 || removed.running) {
                notify(sessionId);
            }
        }
    }

    /**
     * 任务提交时立刻标记运行：总线的 EXECUTION 事件要稍后才到达，
     * 提交后到执行事件之间不能出现「未运行」空窗。
     */
    public void markRunning(String sessionId) {
        if (sessionId == null) return;
        SessionStateImpl state = sessions.computeIfAbsent(sessionId, ignored -> new SessionStateImpl());
        if (!state.running) {
            state.running = true;
            notify(sessionId);
        }
    }

    public boolean isRunning(String sessionId) {
        SessionStateImpl state = sessions.get(sessionId);
        return state != null && state.running;
    }

    public int pendingApprovals(String sessionId) {
        SessionStateImpl state = sessions.get(sessionId);
        return state == null ? 0 : Math.max(0, state.pending.get());
    }

    /** 全部会话的待审批总数，供底栏审批定位显示。 */
    public int totalPendingApprovals() {
        int total = 0;
        for (SessionStateImpl state : sessions.values()) {
            total += Math.max(0, state.pending.get());
        }
        return total;
    }

    /** 存在待审批的会话 id（用于审批定位点击后切换目标标签）。 */
    public Set<String> sessionsWithPending() {
        Set<String> result = new HashSet<>();
        sessions.forEach((sessionId, state) -> {
            if (state.pending.get() > 0) result.add(sessionId);
        });
        return result;
    }

    public Set<String> runningSessions() {
        Set<String> result = new HashSet<>();
        sessions.forEach((sessionId, state) -> {
            if (state.running) result.add(sessionId);
        });
        return result;
    }

    /** 添加状态变更监听器，返回可关闭句柄。 */
    public AutoCloseable addListener(Consumer<SessionState> listener) {
        if (listener != null) listeners.add(listener);
        return () -> removeListener(listener);
    }

    public void removeListener(Consumer<SessionState> listener) {
        listeners.remove(listener);
    }

    private void notify(String sessionId) {
        if (listeners.isEmpty()) return;
        SessionState snapshot = snapshot(sessionId);
        for (Consumer<SessionState> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception failure) {
                // 监听器失败不应阻断其他订阅者或状态机本身
            }
        }
    }

    private SessionState snapshot(String sessionId) {
        SessionStateImpl state = sessions.get(sessionId);
        return new SessionState(sessionId, state != null && state.running,
                state == null ? 0 : Math.max(0, state.pending.get()));
    }
}
