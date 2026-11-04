package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Entering the shared settings page must not convert existing provider identities to OpenAI. */
class AISettingsProviderCompatibilityTest {
    private static final List<String> TYPES=List.of("OpenAI","Claude","Ollama","Gemini","Qwen","ERNIE Bot",
            "Zhipu","Moonshot","MiniMax","Mistral","DeepSeek","Xiaomimimo");
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    private static String map(String method,String value) throws Exception {
        Method mapping=SettingsAIController.class.getDeclaredMethod(method,String.class);
        mapping.setAccessible(true); return (String)mapping.invoke(null,value);
    }
    @Test void everySupportedAdapterSurvivesSettingsDisplayAndSaveMapping() throws Exception {
        for(String type:TYPES) {
            String display=map("mapConfigTypeToProvider",type);
            assertEquals(type,map("mapProviderType",display),"Changed saved adapter type: "+type);
        }
    }
    @Test @SuppressWarnings("unchecked") void sharedSettingsOffersAllSupportedAdaptersWithoutEditingProfiles() throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/settings-ai.fxml"));
                loader.setResources(I18n.bundle()); loader.load();
                ComboBox<String> providers=(ComboBox<String>)loader.getNamespace().get("providerCombo");
                for(String type:TYPES) assertTrue(providers.getItems().contains(map("mapConfigTypeToProvider",type)),"Missing provider: "+type);
            } catch(Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(20,TimeUnit.SECONDS)); if(error.get()!=null) throw new AssertionError(error.get());
    }
}
