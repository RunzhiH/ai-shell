package com.runzhi.aishell.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseAIAdapterTest {

    private static final class TestAdapter extends BaseAIAdapter {
        TestAdapter() {
            super("key", "http://localhost/v1/chat/completions", "m",
                    "http://localhost/v1/chat/completions", "m");
        }

        TestAdapter(String baseUrl) {
            super("key", baseUrl, "m",
                    "http://localhost/v1/chat/completions", "m");
        }

        @Override
        public String sendQuery(String query) { return ""; }
    }

    private final TestAdapter adapter = new TestAdapter();

    @Test
    void extractsPlainStringContent() {
        JsonObject message = new JsonObject();
        message.addProperty("content", "{\"type\":\"final_answer\"}");

        assertEquals("{\"type\":\"final_answer\"}", adapter.extractMessageText(message));
    }

    @Test
    void extractsContentFromTextPartsArray() {
        JsonObject message = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part1 = new JsonObject();
        part1.addProperty("type", "text");
        part1.addProperty("text", "part1");
        JsonObject part2 = new JsonObject();
        part2.addProperty("type", "text");
        part2.addProperty("text", "part2");
        parts.add(part1);
        parts.add(part2);
        message.add("content", parts);

        assertEquals("part1part2", adapter.extractMessageText(message));
    }

    @Test
    void doesNotTreatReasoningContentAsFinalAnswerWhenContentEmpty() {
        JsonObject message = new JsonObject();
        message.addProperty("content", "");
        message.addProperty("reasoning_content", "先分析再输出");

        assertTrue(adapter.extractMessageText(message).isEmpty());
    }

    @Test
    void treatsJsonNullContentAsEmptyWithoutLeakingReasoning() {
        JsonObject message = new JsonObject();
        message.add("content", com.google.gson.JsonNull.INSTANCE);
        message.addProperty("reasoning_content", "推理内容");

        assertTrue(adapter.extractMessageText(message).isEmpty());
    }

    @Test
    void returnsEmptyWhenNothingPresent() {
        JsonObject message = new JsonObject();

        assertTrue(adapter.extractMessageText(message).isEmpty());
    }

    @Test
    void sanitizeBaseUrlRejectsFtpProtocol() {
        assertThrows(IllegalArgumentException.class, () -> new TestAdapter("ftp://example.com/api"));
    }

    @Test
    void sanitizeBaseUrlAcceptsEmptyStringGracefully() {
        assertDoesNotThrow(() -> new TestAdapter("   "));
    }

    @Test
    void sanitizeBaseUrlAcceptsHttpProtocol() {
        assertDoesNotThrow(() -> new TestAdapter("http://example.com/v1/chat/completions"));
    }

    @Test
    void sanitizeBaseUrlAcceptsHttpsProtocol() {
        assertDoesNotThrow(() -> new TestAdapter("https://example.com/v1/chat/completions"));
    }

    @Test
    void sanitizeBaseUrlPreservesOriginalCasing() {
        TestAdapter a = new TestAdapter("https://API.Example.COM/V1/Chat");

        assertEquals("https://API.Example.COM/V1/Chat", a.baseUrl);
    }
}
