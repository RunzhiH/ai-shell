/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.config;

/**
 * 应用程序常量类
 * 
 * 该类集中管理AIShell应用程序中使用的所有常量值，包括应用程序信息、
 * 配置路径、SSH连接参数、系统监控配置、AI服务配置等。通过将常量
 * 集中管理，便于维护和修改应用程序配置。
 * 
 * @author AIShell Team
 * @version 1.0
 * @since 2024
 */
public class AppConstants {
    
    // 应用程序信息
    public static final String APP_NAME = "AIShell";
    public static final String APP_VERSION = "1.0";
    
    // 配置相关
    public static final String CONFIG_DIR = ".aishell";
    public static final String CONNECTIONS_FILE = "connections.json";
    public static final String AI_CONFIG_FILE = "ai_configs.json";
    public static final String DEFAULT_CONFIG_PATH = System.getProperty("user.home") + "/" + CONFIG_DIR + "/" + AI_CONFIG_FILE;
    
    // SSH连接默认值
    public static final int DEFAULT_SSH_PORT = 22;
    public static final String DEFAULT_SSH_HOST = "localhost";
    public static final String DEFAULT_SSH_USERNAME = "username";
    public static final String DEFAULT_SSH_PASSWORD = "password";
    
    // 连接配置
    public static final int MAX_RETRIES = 3;
    public static final int RETRY_DELAY_MS = 2000;
    public static final int CONNECT_TIMEOUT_MS = 30000;
    public static final int SESSION_CONNECT_TIMEOUT_MS = 30000;
    public static final int SERVER_ALIVE_INTERVAL = 60;
    public static final int SERVER_ALIVE_COUNT_MAX = 3;
    public static final int MAX_CONCURRENT_CONNECTIONS = 50; // 最大并发连接数
    
    // 命令执行配置
    public static final int EXEC_CHANNEL_TIMEOUT = 15000; // 15秒命令执行超时
    public static final int COMMAND_BUFFER_SIZE = 8192;   // 增大缓冲区
    
    // 监控配置
    public static final int DF_COMMAND_TIMEOUT = 8000;
    public static final int UPTIME_COMMAND_TIMEOUT = 5000;
    public static final int FREE_COMMAND_TIMEOUT = 5000;
    public static final int TOP_COMMAND_TIMEOUT = 20000;
    public static final int CPUSTAT_COMMAND_TIMEOUT = 10000;
    public static final int NET_COMMAND_TIMEOUT = 10000;
    public static final int PROCESS_COMMAND_TIMEOUT = 15000;
    public static final int NETWORK_COMMAND_TIMEOUT = 15000;
    
    // 系统监控配置
    public static final long MONITOR_INTERVAL_MS = 2000; // 监控间隔2秒
    
    // AI服务配置
    public static final String DEFAULT_OPENAI_MODEL = "gpt-3.5-turbo";
    public static final String DEFAULT_GEMINI_MODEL = "gemini-pro";
    public static final String DEFAULT_OLLAMA_MODEL = "llama2";
    
    // HTTP客户端配置
    public static final int HTTP_CLIENT_CONNECT_TIMEOUT_SECONDS = 10;
    public static final int HTTP_REQUEST_TIMEOUT_SECONDS = 30;
    
    // 其他配置
    public static final int READER_THREAD_JOIN_TIMEOUT = 1000; // 线程等待超时1秒
    public static final int OUTPUT_BUFFER_SIZE = 8192; // 输出缓冲区大小
    public static final int MAX_PENDING_OUTPUT_SIZE = 10240; // 最大待处理输出大小
    public static final int READER_THREAD_SLEEP_MS = 10; // 读取线程休眠时间
    public static final int TIMEOUT_NOTIFY_THRESHOLD_MS = 100; // 超时通知阈值
    
    // UI相关
    public static final String DEFAULT_TERMINAL_PROMPT = "$ ";
    public static final String AI_MODE_PROMPT = "输入自然语言命令，AI将智能转换为Linux命令...";
    public static final String NORMAL_MODE_PROMPT = "输入命令...";
    
    // 模式名称
    public static final String NORMAL_MODE = "普通模式";
    public static final String AI_MODE = "AI模式";
    
    // AI服务默认配置
    public static final String DEFAULT_CLAUDE_MODEL = "claude-3-sonnet-20240229";
    public static final String DEFAULT_ERNIE_BOT_MODEL = "ernie-bot-4.5";
    public static final String DEFAULT_QWEN_MODEL = "qwen-max";
    public static final String DEFAULT_ZHIPU_MODEL = "glm-4";
    public static final String DEFAULT_MOONSHOT_MODEL = "moonshot-v1-8k";
    public static final String DEFAULT_MINIMAX_MODEL = "abab5.5-chat";
    public static final String DEFAULT_MISTRAL_MODEL = "mistral-small-latest";
    public static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";
    public static final String DEFAULT_XIAOMIMIMO_MODEL = "mimo-v2-flash";

    // AI 服务端点
    public static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    public static final String ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages";
    public static final String QWEN_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    public static final String ERNIE_ENDPOINT = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/";
    public static final String GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/";
    public static final String OLLAMA_ENDPOINT = "http://localhost:11434/api/generate";
    public static final String DEEPSEEK_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";
    public static final String MOONSHOT_ENDPOINT = "https://api.moonshot.cn/v1/chat/completions";
    public static final String MISTRAL_ENDPOINT = "https://api.mistral.ai/v1/chat/completions";
    public static final String ZHIPU_ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    // CSS 资源路径（V1：深色默认 style.css；浅色 light-theme.css）
    public static final String CSS_DARK_THEME = "/com/runzhi/aishell/style.css";
    public static final String CSS_LIGHT_THEME = "/com/runzhi/aishell/light-theme.css";
    public static final String CSS_DEFAULT = CSS_DARK_THEME;
    
    // AI服务通用参数
    public static final double DEFAULT_TEMPERATURE = 0.7;
    public static final double DEFAULT_TOP_P = 0.95;
    public static final int DEFAULT_MAX_TOKENS = 1024;
    public static final int DEFAULT_MAX_PREDICT_TOKENS = 1024;
    
    private AppConstants() {
        // 私有构造函数防止实例化
    }
}