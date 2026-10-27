/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.features.bookmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.runzhi.aishell.common.config.AppConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 命令模板管理器 - 单例模式
 */
public class CommandTemplateManager {

    private static final Logger log = LoggerFactory.getLogger(CommandTemplateManager.class);
    private static volatile CommandTemplateManager instance;
    private final List<CommandTemplate> templates;
    private final Gson gson;
    private Path configPath;
    
    private CommandTemplateManager() {
        this.templates = new CopyOnWriteArrayList<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        initialize();
    }
    
    public static CommandTemplateManager getInstance() {
        if (instance == null) {
            synchronized (CommandTemplateManager.class) {
                if (instance == null) {
                    instance = new CommandTemplateManager();
                }
            }
        }
        return instance;
    }
    
    private void initialize() {
        try {
            configPath = java.nio.file.Paths.get(AppConstants.DEFAULT_CONFIG_PATH.replace("ai_configs.json", "command_templates.json"));
            loadTemplates();
        } catch (Exception e) {
            log.error("初始化命令模板管理器失败", e);
            templates.clear();
        }
    }
    
    /**
     * 添加模板
     */
    public void addTemplate(CommandTemplate template) {
        if (!templates.contains(template)) {
            templates.add(template);
            saveTemplates();
        }
    }
    
    /**
     * 删除模板
     */
    public void removeTemplate(String templateId) {
        getTemplateById(templateId).ifPresent(template -> {
            templates.remove(template);
            saveTemplates();
        });
    }
    
    /**
     * 更新模板
     */
    public void updateTemplate(CommandTemplate template) {
        getTemplateById(template.getId()).ifPresent(existing -> {
            existing.setName(template.getName());
            existing.setTemplate(template.getTemplate());
            existing.setDescription(template.getDescription());
            existing.setCategory(template.getCategory());
            existing.setParameters(template.getParameters());
            existing.setExample(template.getExample());
            saveTemplates();
        });
    }
    
    /**
     * 根据 ID 获取模板
     */
    public Optional<CommandTemplate> getTemplateById(String id) {
        return templates.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst();
    }
    
    /**
     * 根据分类获取模板
     */
    public List<CommandTemplate> getTemplatesByCategory(String category) {
        return templates.stream()
                .filter(t -> category.equals(t.getCategory()))
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有模板
     */
    public List<CommandTemplate> getAllTemplates() {
        return new ArrayList<>(templates);
    }
    
    /**
     * 搜索模板
     */
    public List<CommandTemplate> searchTemplates(String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        return templates.stream()
                .filter(t -> t.getName().toLowerCase().contains(lowerKeyword) ||
                        t.getTemplate().toLowerCase().contains(lowerKeyword) ||
                        t.getDescription().toLowerCase().contains(lowerKeyword))
                .collect(Collectors.toList());
    }
    
    /**
     * 渲染模板
     */
    public String renderTemplate(String templateId, Map<String, String> params) {
        return getTemplateById(templateId)
                .map(t -> t.render(params))
                .orElse(null);
    }
    
    /**
     * 保存模板到文件
     */
    private void saveTemplates() {
        try {
            Files.createDirectories(configPath.getParent());
            String json = gson.toJson(templates);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            log.error("保存模板失败", e);
        }
    }
    
    /**
     * 从文件加载模板
     */
    private void loadTemplates() {
        try {
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                CommandTemplate[] loaded = gson.fromJson(json, CommandTemplate[].class);
                templates.clear();
                templates.addAll(Arrays.asList(loaded));
            } else {
                loadDefaultTemplates();
            }
        } catch (IOException e) {
            log.error("加载模板失败", e);
            loadDefaultTemplates();
        }
    }
    
    /**
     * 加载默认模板
     */
    private void loadDefaultTemplates() {
        List<CommandTemplate> defaults = new ArrayList<>();
        
        // 部署相关模板
        CommandTemplate deployApp = new CommandTemplate("部署应用", 
                "cd ${app_path} && git pull && ${build_command} && sudo systemctl restart ${service_name}", 
                "部署发布");
        deployApp.addParameter(new CommandTemplate.TemplateParameter("app_path", "应用路径"));
        CommandTemplate.TemplateParameter buildCommandParam = new CommandTemplate.TemplateParameter("build_command", "构建命令");
        buildCommandParam.setDefaultValue("mvn clean package");
        deployApp.addParameter(buildCommandParam);
        deployApp.addParameter(new CommandTemplate.TemplateParameter("service_name", "服务名称"));
        defaults.add(deployApp);
        
        CommandTemplate dockerRun = new CommandTemplate("运行 Docker 容器",
                "docker run -d --name ${container_name} -p ${host_port}:${container_port} -v ${volume} ${image_name}",
                "部署发布");
        dockerRun.addParameter(new CommandTemplate.TemplateParameter("container_name", "容器名称"));
        dockerRun.addParameter(new CommandTemplate.TemplateParameter("host_port", "宿主机端口"));
        dockerRun.addParameter(new CommandTemplate.TemplateParameter("container_port", "容器端口"));
        CommandTemplate.TemplateParameter volumeParam = new CommandTemplate.TemplateParameter("volume", "挂载卷");
        volumeParam.setDefaultValue("/data:/app/data");
        dockerRun.addParameter(volumeParam);
        dockerRun.addParameter(new CommandTemplate.TemplateParameter("image_name", "镜像名称"));
        defaults.add(dockerRun);
        
        // 监控相关模板
        CommandTemplate tailLog = new CommandTemplate("查看日志",
                "tail -${lines} -f ${log_file}",
                "系统监控");
        tailLog.addParameter(new CommandTemplate.TemplateParameter("lines", "显示行数"));
        CommandTemplate.TemplateParameter linesParam = tailLog.getParameters().get(tailLog.getParameters().size() - 1);
        linesParam.setDefaultValue("100");
        CommandTemplate.TemplateParameter logFileParam = new CommandTemplate.TemplateParameter("log_file", "日志文件路径");
        logFileParam.setDefaultValue("/var/log/app.log");
        tailLog.addParameter(logFileParam);
        defaults.add(tailLog);
        
        CommandTemplate findProcess = new CommandTemplate("查找进程",
                "ps aux | grep ${process_name} | grep -v grep",
                "系统监控");
        findProcess.addParameter(new CommandTemplate.TemplateParameter("process_name", "进程名称"));
        defaults.add(findProcess);
        
        // 网络相关模板
        CommandTemplate curlTest = new CommandTemplate("HTTP 请求测试",
                "curl -X ${method} ${url} ${headers} ${data}",
                "网络工具");
        CommandTemplate.TemplateParameter methodParam = new CommandTemplate.TemplateParameter("method", "HTTP 方法");
        methodParam.setDefaultValue("GET");
        curlTest.addParameter(methodParam);
        curlTest.addParameter(new CommandTemplate.TemplateParameter("url", "请求 URL"));
        CommandTemplate.TemplateParameter headersParam = new CommandTemplate.TemplateParameter("headers", "请求头");
        headersParam.setDefaultValue("-H \"Content-Type: application/json\"");
        curlTest.addParameter(headersParam);
        CommandTemplate.TemplateParameter dataParam = new CommandTemplate.TemplateParameter("data", "请求数据");
        dataParam.setDefaultValue("");
        curlTest.addParameter(dataParam);
        defaults.add(curlTest);
        
        // 数据库相关模板
        CommandTemplate mysqlBackup = new CommandTemplate("MySQL 备份",
                "mysqldump -u ${username} -p${password} ${database} > ${backup_file}",
                "数据库");
        mysqlBackup.addParameter(new CommandTemplate.TemplateParameter("username", "用户名"));
        CommandTemplate.TemplateParameter usernameParam = mysqlBackup.getParameters().get(mysqlBackup.getParameters().size() - 1);
        usernameParam.setDefaultValue("root");
        mysqlBackup.addParameter(new CommandTemplate.TemplateParameter("password", "密码"));
        mysqlBackup.addParameter(new CommandTemplate.TemplateParameter("database", "数据库名"));
        CommandTemplate.TemplateParameter backupFileParam = new CommandTemplate.TemplateParameter("backup_file", "备份文件路径");
        backupFileParam.setDefaultValue("/backup/db_backup.sql");
        mysqlBackup.addParameter(backupFileParam);
        defaults.add(mysqlBackup);
        
        // 文件操作相关模板
        CommandTemplate findFile = new CommandTemplate("查找文件",
                "find ${search_path} -type f -name \"${filename}\" ${size_filter}",
                "文件操作");
        findFile.addParameter(new CommandTemplate.TemplateParameter("search_path", "搜索路径"));
        CommandTemplate.TemplateParameter searchPathParam = findFile.getParameters().get(findFile.getParameters().size() - 1);
        searchPathParam.setDefaultValue(".");
        CommandTemplate.TemplateParameter filenameParam = new CommandTemplate.TemplateParameter("filename", "文件名模式");
        filenameParam.setDefaultValue("*.log");
        findFile.addParameter(filenameParam);
        findFile.addParameter(new CommandTemplate.TemplateParameter("size_filter", "大小过滤"));
        defaults.add(findFile);
        
        templates.addAll(defaults);
        saveTemplates();
    }
}
