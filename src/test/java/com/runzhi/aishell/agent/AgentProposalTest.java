package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentProposalTest {
    private AgentProposal proposal(Instant deadline, java.util.function.BooleanSupplier valid, java.util.function.Consumer<Boolean> callback) {
        return new AgentProposal("C",new AgentProposal.Context("task-3","root","staging:22","/srv","restart nginx"),deadline,valid,callback);
    }

    @Test void decisionIsConsumedOnce() {
        AtomicInteger calls=new AtomicInteger();
        var p=proposal(Instant.now().plusSeconds(60),()->true,approved->{assertTrue(approved);calls.incrementAndGet();});
        assertTrue(p.decide(true)); assertFalse(p.decide(false)); p.expire(); p.invalidate();
        assertEquals(AgentProposal.State.APPROVED,p.state()); assertEquals(1,calls.get());
    }
    @Test void expiryRejectsWithoutAllowingLateApprovalButRetainsTarget() {
        AtomicReference<Boolean> answer=new AtomicReference<>();
        var p=proposal(Instant.now().minusSeconds(1),()->true,answer::set);
        assertFalse(p.decide(true)); assertEquals(AgentProposal.State.EXPIRED,p.state());
        assertEquals(Boolean.FALSE,answer.get()); assertTrue(p.targetValid());
    }
    @Test void reconnectInvalidatesEvenWhenTabIdIsTheSame() {
        Object original=new Object(); AtomicReference<Object> connection=new AtomicReference<>(original);
        var p=proposal(Instant.now().plusSeconds(60),()->connection.get()==original,_ -> {});
        connection.set(new Object());
        assertFalse(p.decide(true)); assertEquals(AgentProposal.State.INVALID,p.state());
        assertEquals("C",p.sessionId()); assertEquals("/srv",p.context().directory());
    }
    @Test void explicitCancellationDisablesBothDecisions() {
        AtomicInteger calls=new AtomicInteger();
        var p=proposal(Instant.now().plusSeconds(60),()->true,_ -> calls.incrementAndGet());
        p.invalidate(); assertFalse(p.decide(false)); assertFalse(p.decide(true)); assertEquals(1,calls.get());
    }
    @Test void expiryResolvesOnlyItsOwnPendingCountAndReplayDoesNotIncrement() throws Exception {
        var tracker=AgentSessionTracker.getInstance();
        try(var subscription=AgentActivityBus.subscribe(tracker::record)) {
            var a=AgentActivityBus.publishApproval("phase4-A","command A",_ -> {});
            var c=AgentActivityBus.publishApproval("phase4-C","command C",_ -> {});
            tracker.record(new AgentActivityBus.Event("phase4-C",AgentActivityBus.Kind.PROPOSAL,"command C",Instant.now(),List.of(),null,c::decide,c.id(),c));
            assertEquals(1,tracker.pendingApprovals("phase4-C"));
            c.expire(); assertEquals(0,tracker.pendingApprovals("phase4-C"));
            assertEquals(1,tracker.pendingApprovals("phase4-A"));
            a.invalidate();
        } finally { tracker.clear("phase4-A");tracker.clear("phase4-C"); }
    }
}
