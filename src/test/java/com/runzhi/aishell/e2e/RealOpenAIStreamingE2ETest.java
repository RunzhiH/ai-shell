package com.runzhi.aishell.e2e;

import com.runzhi.aishell.ai.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RealOpenAIStreamingE2ETest {
    @Test
    void receivesProviderNativeChunks() throws Exception {
        String key = System.getenv("AISHELL_E2E_AI_KEY");
        String endpoint = System.getenv("AISHELL_E2E_AI_ENDPOINT");
        String model = System.getenv("AISHELL_E2E_AI_MODEL");
        Assumptions.assumeTrue(key != null && endpoint != null && model != null,
                "未配置真实 AI E2E 环境变量");
        OpenAIAdapter adapter = new OpenAIAdapter(key, model);
        AIConfig config = adapter.getCurrentConfig();
        config.setBaseUrl(endpoint);
        config.setModel(model);
        adapter.updateConfig(config);
        ArrayList<String> chunks = new ArrayList<>();
        adapter.streamQuery("Reply with exactly AISHELL_E2E_OK", chunks::add, () -> false);
        assertTrue(chunks.size() >= 1);
        assertTrue(String.join("", chunks).contains("AISHELL_E2E_OK"));
    }
}
