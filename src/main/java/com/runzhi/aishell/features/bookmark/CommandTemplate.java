/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.features.bookmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命令模板数据模型
 */
public class CommandTemplate {
    
    private String id;
    private String name;
    private String template;
    private String description;
    private String category;
    private List<TemplateParameter> parameters;
    private String example;
    
    public CommandTemplate() {
        this.id = generateId();
        this.parameters = new ArrayList<>();
    }
    
    public CommandTemplate(String name, String template, String category) {
        this();
        this.name = name;
        this.template = template;
        this.category = category;
    }
    
    private String generateId() {
        return "template_" + System.currentTimeMillis() + "_" + Math.random();
    }
    
    /**
     * 渲染模板，替换参数
     */
    public String render(Map<String, String> params) {
        String result = template;
        for (TemplateParameter param : parameters) {
            String placeholder = "${" + param.getName() + "}";
            String value = params.getOrDefault(param.getName(), param.getDefaultValue());
            result = result.replace(placeholder, value);
        }
        return result;
    }
    
    /**
     * 添加参数
     */
    public void addParameter(TemplateParameter param) {
        parameters.add(param);
    }
    
    /**
     * 移除参数
     */
    public void removeParameter(String paramName) {
        parameters.removeIf(p -> p.getName().equals(paramName));
    }
    
    // Getters and Setters
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getTemplate() {
        return template;
    }
    
    public void setTemplate(String template) {
        this.template = template;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getCategory() {
        return category;
    }
    
    public void setCategory(String category) {
        this.category = category;
    }
    
    public List<TemplateParameter> getParameters() {
        return parameters;
    }
    
    public void setParameters(List<TemplateParameter> parameters) {
        this.parameters = parameters;
    }
    
    public String getExample() {
        return example;
    }
    
    public void setExample(String example) {
        this.example = example;
    }
    
    @Override
    public String toString() {
        return name;
    }
    
    /**
     * 模板参数定义
     */
    public static class TemplateParameter {
        private String name;
        private String description;
        private String defaultValue;
        private boolean required;
        private String type; // text, number, path, choice
        private List<String> choices; // 当 type=choice 时使用
        
        public TemplateParameter() {
            this.required = false;
            this.type = "text";
            this.choices = new ArrayList<>();
        }
        
        public TemplateParameter(String name, String description) {
            this();
            this.name = name;
            this.description = description;
        }
        
        // Getters and Setters
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public String getDefaultValue() {
            return defaultValue;
        }
        
        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }
        
        public boolean isRequired() {
            return required;
        }
        
        public void setRequired(boolean required) {
            this.required = required;
        }
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public List<String> getChoices() {
            return choices;
        }
        
        public void setChoices(List<String> choices) {
            this.choices = choices;
        }
    }
}