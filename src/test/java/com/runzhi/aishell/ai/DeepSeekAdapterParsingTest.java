package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
