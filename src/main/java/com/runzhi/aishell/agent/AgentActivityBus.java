/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-process activity stream used by the tab-bound Agent workspace. */
public final class AgentActivityBus {
    public enum Kind { USER, STATUS, THINK, RESPONSE, TOOL, PROPOSAL, ASK, EXECUTION, SUCCESS, ERROR, CANCELLED }

    /**
     * @param answerHandler 非空表示这是待回答的 ask_user 事件（options 为可选答案）；
     *                      用户选择后回调（null 表示用户跳过/未选择）。
     */
    public record Event(String sessionId, Kind kind, String text, Instant timestamp,
                        List<String> options, Consumer<String> answerHandler,
                        Consumer<Boolean> approvalHandler) {
        public Event(String sessionId, Kind kind, String text, Instant timestamp) {
            this(sessionId, kind, text, timestamp, List.of(), null, null);
        }
        public Event(String sessionId, Kind kind, String text, Instant timestamp,
                     List<String> options, Consumer<String> answerHandler) {
            this(sessionId, kind, text, timestamp, options, answerHandler, null);
        }
    }

    private static final CopyOnWriteArrayList<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();

    private AgentActivityBus() { }
    public static AutoCloseable subscribe(Consumer<Event> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }
    public static void publish(String sessionId, Kind kind, String text) {
        Event event = new Event(sessionId, kind, text == null ? "" : text, Instant.now());
        for (Consumer<Event> listener : LISTENERS) listener.accept(event);
    }

    /** Publishes an ask_user event that the workspace renders as selectable options. */
    public static void publishAsk(String sessionId, String question, List<String> options,
                                  Consumer<String> answerHandler) {
        Event event = new Event(sessionId, Kind.ASK,
                question,
                Instant.now(), List.copyOf(options), answerHandler);
        for (Consumer<Event> listener : LISTENERS) listener.accept(event);
    }

    /** Publishes a command proposal whose approval remains bound to the service safety checks. */
    public static void publishApproval(String sessionId, String proposal, Consumer<Boolean> handler) {
        Event event = new Event(sessionId, Kind.PROPOSAL, proposal, Instant.now(), List.of(), null, handler);
        for (Consumer<Event> listener : LISTENERS) listener.accept(event);
    }
}
