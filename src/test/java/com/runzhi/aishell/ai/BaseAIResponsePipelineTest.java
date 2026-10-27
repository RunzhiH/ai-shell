package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BaseAIResponsePipelineTest {
    @Test void retriesEmptyResponsesAndNormalizesOpenAIContent() {
        TestAdapter adapter = new TestAdapter();
        ArrayDeque<String> responses = new ArrayDeque<>(List.of(" ",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[{\"text\":\"ok\"}]}}]}"));
        String value = adapter.run(() -> responses.removeFirst(), adapter::openAI);
        assertEquals("ok", value);
    }

    @Test void returnsReadableProviderErrorAfterRetries() {
        TestAdapter adapter = new TestAdapter();
        String value = adapter.run(() -> "{\"error\":{\"message\":\"quota exceeded\"}}", root -> "");
        assertTrue(value.startsWith("Error: Test returned no usable content after 3 attempts:"));
        assertTrue(value.contains("quota exceeded"));
    }

    @Test void rejectsTruncatedAndReasoningOnlyResponses() {
        TestAdapter adapter = new TestAdapter();
        assertThrows(IllegalStateException.class, () -> adapter.openAI(json(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}")));
        assertThrows(IllegalStateException.class, () -> adapter.openAI(json(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"reasoning_content\":\"thinking\"}}]}")));
    }

    private static JsonObject json(String value) { return com.google.gson.JsonParser.parseString(value).getAsJsonObject(); }

    private static final class TestAdapter extends BaseAIAdapter {
        TestAdapter() { super("key", "https://example.invalid", "model", "https://example.invalid", "model"); }
        String run(java.util.function.Supplier<String> request, java.util.function.Function<JsonObject,String> extractor) {
            return executeJsonRequest("Test", request, extractor);
        }
        String openAI(JsonObject root) { return extractOpenAICompatible(root); }
        @Override public String sendQuery(String query) { return ""; }
    }
}
