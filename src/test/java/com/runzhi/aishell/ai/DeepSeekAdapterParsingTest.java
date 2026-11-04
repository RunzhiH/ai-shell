package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekAdapterParsingTest {
    @Test
    void extractsStringAndArrayContentWithoutUsingReasoningAsFinalJson() throws Exception {
        DeepSeekAdapter adapter = new DeepSeekAdapter("key", "https://example.invalid", "deepseek-v4-flash");
        Method method = DeepSeekAdapter.class.getDeclaredMethod("extractContentOnly", JsonObject.class);
        method.setAccessible(true);
        JsonObject stringMessage = com.google.gson.JsonParser.parseString(
                "{\"content\":\"{\\\"type\\\":\\\"final_answer\\\"}\"}").getAsJsonObject();
        JsonObject arrayMessage = com.google.gson.JsonParser.parseString(
                "{\"content\":[{\"text\":\"hello\"},{\"text\":\" world\"}],"
                        + "\"reasoning_content\":\"private\"}").getAsJsonObject();

        assertEquals("{\"type\":\"final_answer\"}", method.invoke(adapter, stringMessage));
        assertEquals("hello world", method.invoke(adapter, arrayMessage));
    }

    @Test
    void flashAndV4ModelsDisableThinkingInSerializedSyncAndStreamBodies() {
        for (String model : List.of("deepseek-flash", "deepseek-v4", "deepseek-v4-flash", "deepseek-v4.1")) {
            DeepSeekAdapter adapter = new DeepSeekAdapter("key", "https://example.invalid", model);
            JsonObject sync = payload(adapter.buildRequest("hello", false));
            JsonObject stream = payload(adapter.buildRequest("hello", true));

            assertThinkingDisabled(sync);
            assertThinkingDisabled(stream);
            assertEquals(model, sync.get("model").getAsString());
            assertEquals("hello", sync.getAsJsonArray("messages").get(0).getAsJsonObject()
                    .get("content").getAsString());
            assertEquals(1024, sync.get("max_tokens").getAsInt());
            assertFalse(sync.get("stream").getAsBoolean());
            assertTrue(stream.get("stream").getAsBoolean());
        }
    }

    @Test
    void reasonerUnknownAndBoundaryModelsDoNotReceiveThinking() {
        for (String model : List.of("deepseek-reasoner", "deepseek-chat", "deepseek-v40", "deepseek-v4x",
                "deepseek-flash-extra")) {
            DeepSeekAdapter adapter = new DeepSeekAdapter("key", "https://example.invalid", model);
            JsonObject body = payload(adapter.buildRequest("hello", false));
            assertFalse(body.has("thinking"), model + " must not receive unsupported thinking: " + body);
        }
    }

    @Test
    void userGenerationSettingsRemainInTheSerializedPayload() {
        DeepSeekAdapter adapter = new DeepSeekAdapter("key", "https://example.invalid", "deepseek-flash");
        AIConfig config = new AIConfig("flash", "DeepSeek", "key", "https://example.invalid", "deepseek-flash");
        config.setMaxTokens(2048);
        config.setTemperature(0.7);
        config.setTopP(0.9);
        adapter.updateConfig(config);

        JsonObject body = payload(adapter.buildRequest("hello", false));
        assertEquals(2048, body.get("max_tokens").getAsInt());
        assertEquals(0.7, body.get("temperature").getAsDouble(), 0.0001);
        assertEquals(0.9, body.get("top_p").getAsDouble(), 0.0001);
        assertThinkingDisabled(body);
    }

    private static JsonObject payload(JsonObject body) {
        return JsonParser.parseString(body.toString()).getAsJsonObject();
    }

    private static void assertThinkingDisabled(JsonObject body) {
        assertTrue(body.has("thinking"), body.toString());
        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
    }
}
