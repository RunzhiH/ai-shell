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
    /** 用户明确选择并持久化的默认配置名；临时回退不会覆盖它。 */
    private volatile String defaultConfigName;
    private Gson gson;
    private static final String ENCRYPTED_PREFIX = "enc:";
    private static final String DEFAULT_CONFIG_KEY = "ai.default.config";
    /** 旧版本键，仅用于迁移已记录的默认选择。 */
    private static final String LEGACY_CURRENT_CONFIG_KEY = "ai.current.config";
    private static final String FALLBACK_CONFIG_NAME = "默认OpenAI";
    /** Agent 需要完整 JSON/工具调用，至少给它比普通对话更大的输出预算。 */
    static final int AGENT_MIN_MAX_TOKENS = 4096;
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
                if (currentConfig != null && java.util.Objects.equals(currentConfig.getName(), config.getName())) {
                    currentConfig = config;
                }
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
        if (java.util.Objects.equals(defaultConfigName, name)) {
            defaultConfigName = null;
            clearDefaultPreference();
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
                rememberDefault(name);
                return;
            }
        }
    }

    public AIConfig getCurrentConfig() {
        return currentConfig;
    }

    /** 用户明确记录、用于下次启动的默认配置名。 */
    public String getDefaultConfigName() {
        return defaultConfigName;
    }

    /** 明确设置默认配置：切换当前配置并立即持久化，供设置页/模型切换使用。 */
    public boolean setDefaultConfig(String name) {
        AIConfig config = getConfigByName(name);
        if (config == null) return false;
        currentConfig = config;
        rememberDefault(config.getName());
        return true;
    }

    /**
     * 运行期临时回退（例如当前配置暂不可用）：只改变内存中的 currentConfig，
     * 不覆盖用户记录的默认选择，避免下次启动被回退值带偏。
     */
    public void useFallbackConfig(String name) {
        AIConfig config = getConfigByName(name);
        if (config != null) currentConfig = config;
    }

    private void rememberDefault(String name) {
        if (name == null || name.isBlank()) return;
        defaultConfigName = name;
        preferences.put(DEFAULT_CONFIG_KEY, name);
        // 同步旧键，保证回退到旧版本时仍能读到同一个默认选择。
        preferences.put(LEGACY_CURRENT_CONFIG_KEY, name);
        flushPreferences();
    }

    private void clearDefaultPreference() {
        preferences.remove(DEFAULT_CONFIG_KEY);
        preferences.remove(LEGACY_CURRENT_CONFIG_KEY);
        flushPreferences();
    }

    private void flushPreferences() {
        try {
            preferences.flush();
        } catch (java.util.prefs.BackingStoreException failure) {
            logger.warn("保存 AI 默认配置选择失败: {}", failure.getMessage());
        }
    }

    /** 启动/重新加载时恢复默认选择；旧键只在没有新键时读取。 */
    private void restoreDefaultConfig() {
        String preferred = preferences.get(DEFAULT_CONFIG_KEY, null);
        if (preferred == null || preferred.isBlank()) {
            preferred = preferences.get(LEGACY_CURRENT_CONFIG_KEY, null);
        }
        if (preferred == null || preferred.isBlank()) preferred = "用户设置";
        AIConfig selected = getConfigByName(preferred);
        if (selected == null) selected = getConfigByName(FALLBACK_CONFIG_NAME);
        if (selected == null && !configs.isEmpty()) selected = configs.get(0);
        currentConfig = selected;
        if (selected == null) {
            defaultConfigName = null;
            return;
        }
        rememberDefault(selected.getName());
        logger.info("默认 AI 配置: {} (type={}, model={})",
                selected.getName(), selected.getType(), selected.getModel());
    }

    public AIAdapter getCurrentAIAdapter() {
        if (currentConfig == null) {
            return null;
        }
        
        return createAdapterForConfig(currentConfig);
    }

    /**
     * Agent 专用适配器：在用户配置基础上保证至少 AGENT_MIN_MAX_TOKENS 的输出预算。
     * 只影响本次 Agent 请求，不修改也不持久化用户设置。
     */
    public AIAdapter getAgentAIAdapter() {
        AIConfig config = currentConfig;
        if (config == null) return null;
        if (config.getMaxTokens() >= AGENT_MIN_MAX_TOKENS) return createAdapterForConfig(config);
        AIConfig agentConfig = gson.fromJson(gson.toJson(config), AIConfig.class);
        if (agentConfig == null) return null;
        agentConfig.setMaxTokens(AGENT_MIN_MAX_TOKENS);
        return createAdapterForConfig(agentConfig);
    }
    
    /**
     * 根据配置创建对应的AI适配器
     */
    private AIAdapter createAdapterForConfig(AIConfig config) {
        AIAdapter adapter = switch (config.getType()) {
            case "OpenAI" -> new OpenAIAdapter(config.getApiKey(), config.getModel());
            case "Claude" -> new ClaudeAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Ollama" -> new OllamaAdapter(config.getBaseUrl(), config.getModel());
            case "Gemini" -> new GeminiAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Qwen" -> new QwenAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "ERNIE Bot" -> createERNIEBotAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Zhipu" -> new ZhipuAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Moonshot" -> new MoonshotAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "MiniMax" -> createMiniMaxAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Mistral" -> new MistralAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "DeepSeek" -> new DeepSeekAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            case "Xiaomimimo" -> new XiaomiMimoAdapter(config.getApiKey(), config.getBaseUrl(), config.getModel());
            // 默认按 OpenAI 兼容协议处理，但保留用户配置的 baseUrl（自定义网关/中转）
            default -> new OpenAIAdapter(config.getApiKey(), config.getModel());
        };
        // 所有供应商都必须写入运行时配置，设置页的 temperature/top_p/max_tokens 才会生效。
        if (adapter instanceof ERNIEBotAdapter || adapter instanceof MiniMaxAdapter) {
            // 这两个适配器在构造时已把 "id:secret" / "key:group" 拆成独立字段，不能再被完整 key 覆盖。
            ((BaseAIAdapter) adapter).attachRuntimeConfig(config);
        } else {
            ((BaseAIAdapter) adapter).updateConfig(config);
        }
        return adapter;
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
                rememberDefault(config.getName());
                return;
            }
        }
        // 类型匹配不到时回退到类名匹配
        String adapterName = adapter.getName();
        for (AIConfig config : configs) {
            if (config.getType().equals(adapterName)) {
                currentConfig = config;
                rememberDefault(config.getName());
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
                restoreDefaultConfig();
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
        restoreDefaultConfig();
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