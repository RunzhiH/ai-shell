package com.runzhi.aishell.ai.stream;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.StreamingAIAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AIStreamingExecutorTest {
    @Test
    void emitsLifecycleEventsInOrder() throws Exception {
        AIAdapter adapter = new AIAdapter() {
            public String sendQuery(String query) { return "answer"; }
            public boolean isConfigured() { return true; }
            public String getName() { return "test"; }
            public String generateCommand(String value) { return value; }
            public String explainCommand(String value) { return value; }
            public String getHelp(String value) { return value; }
            public void setConfig(Map<String, Object> config) {}
        };
        List<AIStreamEvent.Type> events = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        new AIStreamingExecutor().submit(adapter, "hello", event -> {
            events.add(event.type());
            if (event.type() == AIStreamEvent.Type.COMPLETED) completed.countDown();
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(AIStreamEvent.Type.STARTED, AIStreamEvent.Type.DELTA,
                AIStreamEvent.Type.COMPLETED), events);
    }

    @Test
    void preservesNativeStreamingChunks() throws Exception {
        StreamingAIAdapter adapter = new StreamingAIAdapter() {
            public void streamQuery(String query, java.util.function.Consumer<String> delta,
                                    java.util.function.BooleanSupplier cancelled) {
                delta.accept("first");
                delta.accept("second");
            }
            public String sendQuery(String query) { return "fallback"; }
            public boolean isConfigured() { return true; }
            public String getName() { return "stream"; }
            public String generateCommand(String value) { return value; }
            public String explainCommand(String value) { return value; }
            public String getHelp(String value) { return value; }
        };
        List<String> deltas = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        new AIStreamingExecutor().submit(adapter, "hello", event -> {
            if (event.type() == AIStreamEvent.Type.DELTA) deltas.add(event.text());
            if (event.type() == AIStreamEvent.Type.COMPLETED) completed.countDown();
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("first", "second"), deltas);
    }
}
