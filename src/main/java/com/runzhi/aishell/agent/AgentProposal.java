package com.runzhi.aishell.agent;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** One human decision, bound to the original connection and a wall-clock deadline. */
public final class AgentProposal {
    public enum State { PENDING, APPROVED, REJECTED, EXPIRED, INVALID }
    public record Context(String task, String user, String host, String directory, String impact, CommandRisk risk) {
        public Context(String task,String user,String host,String directory,String impact) {
            this(task,user,host,directory,impact,CommandRisk.MEDIUM);
        }
    }
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-approval-deadlines"); t.setDaemon(true); return t;
    });
    private final String id = UUID.randomUUID().toString();
    private final String sessionId;
    private final Context context;
    private final Instant deadline;
    private final BooleanSupplier valid;
    private final Consumer<Boolean> decision;
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final CompletableFuture<State> completed = new CompletableFuture<>();
    private volatile ScheduledFuture<?> monitor;

    public AgentProposal(String sessionId, Context context, Instant deadline, BooleanSupplier valid, Consumer<Boolean> decision) {
        this.sessionId=sessionId; this.context=context; this.deadline=deadline;
        this.valid=valid; this.decision=decision;
    }
    void start() {
        monitor=TIMER.scheduleAtFixedRate(this::refresh, 1, 1, TimeUnit.SECONDS);
        if(state.get()!=State.PENDING) monitor.cancel(false);
    }
    public String id() { return id; }
    public String sessionId() { return sessionId; }
    public Context context() { return context; }
    public Instant deadline() { return deadline; }
    public CompletableFuture<State> completed() { return completed; }
    public State state() { refresh(); return state.get(); }
    public boolean targetValid() {
        try { return valid.getAsBoolean(); } catch(RuntimeException unavailable) { return false; }
    }
    public void refresh() {
        if(state.get()!=State.PENDING) return;
        if(!targetValid()) finish(State.INVALID);
        else if(!Instant.now().isBefore(deadline)) finish(State.EXPIRED);
    }
    public boolean decide(boolean approved) {
        refresh(); return finish(approved ? State.APPROVED : State.REJECTED);
    }
    public void invalidate() { finish(State.INVALID); }
    public void expire() { finish(State.EXPIRED); }
    private boolean finish(State value) {
        if(!state.compareAndSet(State.PENDING,value)) return false;
        if(monitor!=null) monitor.cancel(false);
        completed.complete(value);
        AgentAuditLogger.getInstance().record("approval_"+value.name().toLowerCase(java.util.Locale.ROOT),
                sessionId,context.risk(),context.impact(),id);
        AgentActivityBus.proposalChanged(this);
        decision.accept(value==State.APPROVED);
        return true;
    }
}
