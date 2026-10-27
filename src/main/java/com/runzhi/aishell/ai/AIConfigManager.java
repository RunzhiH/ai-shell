/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.logging.LoggerManager;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

public class AIConfigManager {
    private static final String CONFIG_FILE_PATH = AppConstants.DEFAULT_CONFIG_PATH;
    private static final Logger logger = LoggerManager.getLogger(AIConfigManager.class);
    private static volatile AIConfigManager instance;
    // 使用 CopyOnWriteArrayList：写操作（FX 线程增删配置）远少于读操作（AI 命令执行器迭代配置），
    // 读不加锁，写整体复制，避免并发修改异常。
    private final List<AIConfig> configs = new CopyOnWriteArrayList<>();
    private volatile AIConfig currentConfig;
    private Gson gson;
    private static final String ENCRYPTED_PREFIX = "enc:";
    private final Preferences preferences = Preferences.userNodeForPackage(AIConfigManager.class);

    private AIConfigManager() {
        gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
        loadConfigs();
    }

    public static AIConfigManager getInstance() {
        if (instance == null) {
            synchronized (AIConfigManager.class) {
                if (instance == null) {
                    instance = new AIConfigManager();
                }
            }
        }
        return instance;
    }

    public void addConfig(AIConfig config) {
        // 检查是否已存在同名配置
        for (int i = 0; i < configs.size(); i++) {
            if (java.util.Objects.equals(configs.get(i).getName(), config.getName())) {
                configs.set(i, config); // 更新现有配置
                saveConfigs();
                return;
            }
        }
        configs.add(config);
        saveConfigs();
    }

    public void removeConfig(String name) {
        configs.removeIf(config -> java.util.Objects.equals(config.getName(), name));
        if (currentConfig != null && currentConfig.getName().equals(name)) {
            currentConfig = null;
        }
        saveConfigs();
    }

    public List<AIConfig> getAllConfigs() {
        return new ArrayList<>(configs);
    }
    public AIConfig getConfigByName(String name) {
        for (AIConfig config : configs) {
            if (java.util.Objects.equals(config.getName(), name)) {
                return config;
            }
        }
        return null;
    }

    public void setCurrentConfig(String name) {
        for (AIConfig config : configs) {
            if (java.util.Objects.equals(config.getName(), name)) {
                currentConfig = config;
                preferences.put("ai.current.config", name);
                return;
            }
        }
    }

    public AIConfig getCurrentConfig() {
        return currentConfig;
    }

    public AIAdapter getCurrentAIAdapter() {
        if (currentConfig == null) {
            return null;
        }
        
        return createAdapterForConfig(currentConfig);
    }
    
    /**
     * 根据配置创建对应的AI适配器
     */
    private AIAdapter createAdapterForConfig(AIConfig config) {
        switch (config.getType()) {
            case "OpenAI":
                OpenAIAdapter openAI = new OpenAIAdapter(config.getApiKey(), config.getModel());
                openAI.updateConfig(config);
                return openAI;
            case "Claude":
                return new ClaudeAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Ollama":
                return new OllamaAdapter(config.getBaseUrl(), config.getModel());
            case "Gemini":
                return new GeminiAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Qwen":
                return new QwenAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "ERNIE Bot":
                return createERNIEBotAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Zhipu":
                return new ZhipuAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Moonshot":
                return new MoonshotAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "MiniMax":
                return createMiniMaxAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Mistral":
                return new MistralAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "DeepSeek":
                return new DeepSeekAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Xiaomimimo":
                return new XiaomiMimoAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            default:
                // 默认按 OpenAI 兼容协议处理，但保留用户配置的 baseUrl（自定义网关/中转）
                OpenAIAdapter fallback = new OpenAIAdapter(config.getApiKey(), config.getModel());
                fallback.updateConfig(config);
                return fallback;
        }
    }
    
    /**
     * 创建ERNIE Bot适配器
     */
    private AIAdapter createERNIEBotAdapter(String apiKey, String baseUrl, String model) {
        String[] keys = apiKey.split(":");
        if (keys.length >= 2) {
            return new ERNIEBotAdapter(keys[0], keys[1], baseUrl, model);
        } else {
            return new OpenAIAdapter(apiKey, model); // 默认处理
        }
    }
    
    /**
     * 创建MiniMax适配器
     */
    private AIAdapter createMiniMaxAdapter(String apiKey, String baseUrl, String model) {
        String[] miniMaxKeys = apiKey.split(":");
        if (miniMaxKeys.length >= 2) {
            return new MiniMaxAdapter(miniMaxKeys[0], miniMaxKeys[1], baseUrl, model);
        } else {
            return new OpenAIAdapter(apiKey, model); // 默认处理
        }
    }
    
    /**
     * 设置当前AI适配器（通过适配器对象）
     * 由于AIAdapter没有直接的名称，我们通过适配器类型来匹配配置
     */
    public void setCurrentAIAdapter(AIAdapter adapter) {
        // 通过适配器的 provider 类型匹配配置（getName() 返回类名去 "Adapter"，
        // "ERNIEBot"≠"ERNIE Bot" 等命名差异会把匹配打空），并持久化当前选择（2026-10-23 审计 P1-8/附）
        String provider = providerTypeOf(adapter);
        for (AIConfig config : configs) {
            if (config.getType().equals(provider)) {
                currentConfig = config;
                preferences.put("ai.current.config", config.getName());
                return;
            }
        }
        // 类型匹配不到时回退到类名匹配
        String adapterName = adapter.getName();
        for (AIConfig config : configs) {
            if (config.getType().equals(adapterName)) {
                currentConfig = config;
                preferences.put("ai.current.config", config.getName());
                return;
            }
        }
    }

    /** 从适配器实例推断其 provider 类型标识（与 AIConfig.type 口径一致）。 */
    private static String providerTypeOf(AIAdapter adapter) {
        String name = adapter.getClass().getSimpleName();
        return switch (name) {
            case "OpenAIAdapter" -> "OpenAI";
            case "ClaudeAdapter" -> "Claude";
            case "OllamaAdapter" -> "Ollama";
            case "GeminiAdapter" -> "Gemini";
            case "QwenAdapter" -> "Qwen";
            case "ERNIEBotAdapter" -> "ERNIE Bot";
            case "ZhipuAdapter" -> "Zhipu";
            case "MoonshotAdapter" -> "Moonshot";
            case "MiniMaxAdapter" -> "MiniMax";
            case "MistralAdapter" -> "Mistral";
            case "DeepSeekAdapter" -> "DeepSeek";
            case "XiaomiMimoAdapter" -> "Xiaomimimo";
            default -> name.replace("Adapter", "");
        };
    }
    
    /**
     * 保存当前AI配置
     */
    public void saveCurrentAIConfig() {
        // 保存配置到文件
        saveConfigs();
    }
    
    /**
     * 加载AI配置
     */
    public void loadAIConfig() {
        // 重新加载配置
        loadConfigs();
    }

    private void loadConfigs() {
        File configFile = new File(CONFIG_FILE_PATH);
        
        // 确保配置目录存在
        configFile.getParentFile().mkdirs();
        
        if (!configFile.exists()) {
            // 创建默认配置
            logger.info("配置文件不存在，创建默认配置: {}", CONFIG_FILE_PATH);
            createDefaultConfigs();
            saveConfigs();
            return;
        }

        // 检查文件是否为空或只包含空白字符
        if (configFile.length() == 0) {
            logger.warn("配置文件为空，创建默认配置: {}", CONFIG_FILE_PATH);
            createDefaultConfigs();
            saveConfigs();
            return;
        }

        String content = "";
        // 读取文件内容并检查是否为空白，使用UTF-8编码
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(CONFIG_FILE_PATH));
            content = new String(bytes, "UTF-8").trim(); // 使用字符串指定编码，避免StandardCharsets问题
            if (content.isEmpty()) {
                logger.warn("配置文件内容为空，创建默认配置: {}", CONFIG_FILE_PATH);
                createDefaultConfigs();
                saveConfigs();
                return;
            }
            
            // 检查内容是否为有效的JSON格式
            content = content.trim();
            if (!(content.startsWith("[") && content.endsWith("]")) && 
                !(content.startsWith("{") && content.endsWith("}"))) {
                logger.error("配置文件格式错误，不是有效的JSON格式: {}", content);
                createDefaultConfigs();
                saveConfigs();
                return;
            }
        } catch (IOException e) {
            logger.error("读取配置文件失败: {}", e.getMessage(), e);
            createDefaultConfigs();
            return;
        }

        try (StringReader stringReader = new StringReader(content)) {
            Type listType = new TypeToken<ArrayList<AIConfig>>(){}.getType();
            List<AIConfig> loadedConfigs = gson.fromJson(stringReader, listType);
            if (loadedConfigs != null) {
                loadedConfigs.forEach(this::decryptApiKey);
                configs.clear();
                configs.addAll(loadedConfigs);
                String currentName = preferences.get("ai.current.config", "用户设置");
                currentConfig = configs.stream().filter(c -> currentName.equals(c.getName())).findFirst()
                        .orElseGet(() -> configs.stream().filter(c -> "默认OpenAI".equals(c.getName())).findFirst().orElse(null));
                logger.info("成功加载 {} 个AI配置", configs.size());
                saveConfigs(); // 同时把历史明文密钥迁移为 enc: 格式。
            } else {
                logger.warn("配置文件解析为空，创建默认配置");
                createDefaultConfigs();
            }
        } catch (JsonSyntaxException e) {
            logger.error("JSON解析错误: {}", e.getMessage());
            createDefaultConfigs(); // JSON格式错误时创建默认配置
            saveConfigs();
        }
    }

    private void saveConfigs() {
        File configFile = new File(CONFIG_FILE_PATH);
        configFile.getParentFile().mkdirs(); // 确保目录存在

        try (java.io.Writer writer = Files.newBufferedWriter(configFile.toPath(), StandardCharsets.UTF_8)) {
            List<AIConfig> persisted = configs.stream().map(this::encryptedCopy).toList();
            gson.toJson(persisted, writer);
            logger.info("成功保存 {} 个AI配置到文件: {}", configs.size(), CONFIG_FILE_PATH);
        } catch (IOException e) {
            logger.error("保存配置文件时发生错误: {}", e.getMessage(), e);
            
            // 尝试备份当前配置到临时位置
            try {
                File backupFile = new File(CONFIG_FILE_PATH + ".backup");
                try (java.io.Writer backupWriter = Files.newBufferedWriter(backupFile.toPath(), StandardCharsets.UTF_8)) {
                    gson.toJson(configs.stream().map(this::encryptedCopy).toList(), backupWriter);
                    logger.info("配置备份已保存到: {}", backupFile.getAbsolutePath());
                }
            } catch (IOException backupEx) {
                logger.error("备份配置也失败: {}", backupEx.getMessage(), backupEx);
            }
        }
    }

    private void createDefaultConfigs() {
        // 失败/初始化路径先清空旧配置：否则反复加载会在旧配置上重复追加 11 个默认配置（2026-10-23 审计 P1-7）
        configs.clear();
        currentConfig = null;
        // 添加默认的OpenAI配置
        configs.add(new AIConfig("默认OpenAI", "OpenAI", "",
                "https://api.openai.com/v1/chat/completions", AppConstants.DEFAULT_OPENAI_MODEL));
                
        // 添加其他AI服务的默认配置
        configs.add(new AIConfig("Gemini Pro", "Gemini", "",
                "https://generativelanguage.googleapis.com/v1beta/models/", AppConstants.DEFAULT_GEMINI_MODEL));
                
        configs.add(new AIConfig("通义千问", "Qwen", "",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation", AppConstants.DEFAULT_QWEN_MODEL));
                
        configs.add(new AIConfig("文心一言4.5", "ERNIE Bot", "",
                "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/", AppConstants.DEFAULT_ERNIE_BOT_MODEL));
                
        configs.add(new AIConfig("智谱AI", "Zhipu", "",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions", AppConstants.DEFAULT_ZHIPU_MODEL));
                
        configs.add(new AIConfig("月之暗面", "Moonshot", "",
                "https://api.moonshot.cn/v1/chat/completions", AppConstants.DEFAULT_MOONSHOT_MODEL));
                
        configs.add(new AIConfig("MiniMax", "MiniMax", "",
                "https://api.minimaxi.com/v1/chat/completions", AppConstants.DEFAULT_MINIMAX_MODEL));
                
        configs.add(new AIConfig("Mistral", "Mistral", "",
                "https://api.mistral.ai/v1/chat/completions", AppConstants.DEFAULT_MISTRAL_MODEL));
                
        configs.add(new AIConfig("Ollama本地", "Ollama", "", 
                "http://localhost:11434/api/generate", AppConstants.DEFAULT_OLLAMA_MODEL));
                
        configs.add(new AIConfig("DeepSeek", "DeepSeek", "",
                "https://api.deepseek.com/v1/chat/completions", AppConstants.DEFAULT_DEEPSEEK_MODEL));
                
        configs.add(new AIConfig("小米Mimo", "Xiaomimimo", "",
                "https://api.xiaomimimo.com/v1/chat/completions", AppConstants.DEFAULT_XIAOMIMIMO_MODEL));
    }

    private AIConfig encryptedCopy(AIConfig source) {
        AIConfig copy = gson.fromJson(gson.toJson(source), AIConfig.class);
        if (copy.getApiKey() != null && !copy.getApiKey().isBlank()) {
            // 保持幂等，避免旧流程把已经持久化的密文再次加密。
            if (!copy.getApiKey().startsWith(ENCRYPTED_PREFIX)) {
                copy.setApiKey(ENCRYPTED_PREFIX
                        + com.runzhi.aishell.common.util.PasswordEncryptionUtil.encryptPassword(copy.getApiKey()));
            }
        }
        return copy;
    }

    private void decryptApiKey(AIConfig config) {
        String value = config.getApiKey();
        if (value == null || !value.startsWith(ENCRYPTED_PREFIX)) return;
        try {
            config.setApiKey(com.runzhi.aishell.common.util.PasswordEncryptionUtil
                    .decryptPassword(value.substring(ENCRYPTED_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            logger.warn("AI 配置 {} 的密钥无法解密，已清空", config.getName());
            config.setApiKey("");
        }
    }
}