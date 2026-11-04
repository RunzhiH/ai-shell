package com.runzhi.aishell.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 会话状态追踪：按 proposalId 核销、任务终结清零、会话隔离（v6 阶段 2 复审修正）。
 */
class AgentSessionTrackerTest {

    private final AgentSessionTracker tracker = AgentSessionTracker.getInstance();

    @AfterEach
    void cleanup() {
        for (String session : Set.copyOf(tracker.sessionsWithPending())) tracker.clear(session);
        for (String session : Set.copyOf(tracker.runningSessions())) tracker.clear(session);
    }

    private AgentActivityBus.Event event(String session, AgentActivityBus.Kind kind,
                                        String proposalId, Consumer<Boolean> approval) {
        return new AgentActivityBus.Event(session, kind, "text", Instant.now(),
                List.of(), null, approval, proposalId);
    }

    @Test
    void proposalCountsAsPendingUntilDecision() {
        AtomicInteger decisions = new AtomicInteger();
        Consumer<Boolean> handler = approved -> decisions.incrementAndGet();

        tracker.record(event("s1", AgentActivityBus.Kind.PROPOSAL, "p1", handler));
        assertEquals(1, tracker.pendingApprovals("s1"));
        assertEquals(1, tracker.totalPendingApprovals());
        assertFalse(tracker.isRunning("s1"));

        tracker.trackDecision("s1", "p1", handler).accept(true);
        assertEquals(0, tracker.pendingApprovals("s1"));
        assertEquals(1, decisions.get());
    }

    @Test
    void replayedOldDecisionDoesNotStealNewPendingCount() {
        // 复审复现场景：P1 已处理，同会话产生 P2；切换回来再点历史 P1，
        // P2 的待审批计数不能被旧卡片的重放扣掉。
        Consumer<Boolean> p1Handler = approved -> { };
        tracker.record(event("s", AgentActivityBus.Kind.PROPOSAL, "P1", p1Handler));
        tracker.trackDecision("s", "P1", p1Handler).accept(true);

        tracker.record(event("s", AgentActivityBus.Kind.PROPOSAL, "P2", approved -> { }));
        assertEquals(1, tracker.pendingApprovals("s"));

        // 历史卡片重放（同一 handler 再次提交）
        tracker.trackDecision("s", "P1", p1Handler).accept(true);
        assertEquals(1, tracker.pendingApprovals("s"), "P2 仍待审批，重放旧卡片不得扣减其计数");
    }

    @Test
    void taskTerminationResolvesPendingAndBlocksReplayedCards() {
        // 取消/失败/完成时审批门被中断或超时，不会再经 UI 决策路径核销计数
        tracker.record(event("s", AgentActivityBus.Kind.PROPOSAL, "P1", approved -> { }));
        tracker.record(event("s", AgentActivityBus.Kind.PROPOSAL, "P2", approved -> { }));
        assertEquals(2, tracker.pendingApprovals("s"));

        tracker.record(event("s", AgentActivityBus.Kind.CANCELLED, null, null));
        assertEquals(0, tracker.pendingApprovals("s"), "任务取消后其未决提议须全部失效");
        assertFalse(tracker.isRunning("s"));

        // 紧接着旧卡片被点击：无句柄回调、计数不再为负
        tracker.trackDecision("s", "P1", approved -> fail("已终结提议不应再回调业务句柄")).accept(true);
        assertEquals(0, tracker.pendingApprovals("s"));
    }

    @Test
    void runningStateIsPerSession() {
        tracker.markRunning("a");
        tracker.markRunning("b");
        assertTrue(tracker.isRunning("a"));
        assertTrue(tracker.isRunning("b"));
        assertEquals(Set.of("a", "b"), tracker.runningSessions());

        tracker.record(event("a", AgentActivityBus.Kind.SUCCESS, null, null));
        assertFalse(tracker.isRunning("a"));
        assertTrue(tracker.isRunning("b"));
    }

    @Test
    void autoApprovedProposalsDoNotOccupyApproval() {
        tracker.record(event("s2", AgentActivityBus.Kind.PROPOSAL, null, null));
        assertEquals(0, tracker.pendingApprovals("s2"));
    }

    @Test
    void legacyProposalsWithoutIdStillResolveOncePerSubmit() {
        // 无 proposalId 的旧式事件：每次提交都核销一次，但计数钳制不为负
        Consumer<Boolean> handler = approved -> { };
        tracker.record(event("s3", AgentActivityBus.Kind.PROPOSAL, null, handler));
        tracker.trackDecision("s3", null, handler).accept(false);
        tracker.trackDecision("s3", null, handler).accept(false);
        assertEquals(0, tracker.pendingApprovals("s3"));
    }

    @Test
    void listenersSeeStateTransitions() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        AutoCloseable subscription = tracker.addListener(state -> notifications.incrementAndGet());
        try {
            int before = notifications.get();
            tracker.record(event("s4", AgentActivityBus.Kind.PROPOSAL, "p4", approved -> { }));
            assertTrue(notifications.get() > before);
        } finally {
            subscription.close();
        }
    }

    @Test
    void totalPendingCountsAcrossSessionsAndClearResets() {
        tracker.record(event("x", AgentActivityBus.Kind.PROPOSAL, "x1", approved -> { }));
        tracker.record(event("x", AgentActivityBus.Kind.PROPOSAL, "x2", approved -> { }));
        tracker.record(event("y", AgentActivityBus.Kind.PROPOSAL, "y1", approved -> { }));
        assertEquals(3, tracker.totalPendingApprovals());
        assertEquals(Set.of("x", "y"), tracker.sessionsWithPending());
        tracker.clear("x");
        assertEquals(1, tracker.totalPendingApprovals());
        assertEquals(Set.of("y"), tracker.sessionsWithPending());
    }
}
