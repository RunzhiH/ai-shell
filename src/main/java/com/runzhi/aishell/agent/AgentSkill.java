/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

/**
 * Agent 技能：一条按操作系统分类的可复用提示词模板。
 * <p>
 * 用户通过 /skill &lt;name&gt; 把技能填充为当前任务目标，Agent 按模板执行巡检/排查流程。
 *
 * @param name        技能名（命令行标识，全库唯一）
 * @param os          目标操作系统：linux / macos / windows / all
 * @param description 一句话说明（/skills 列表里展示）
 * @param prompt      发送给 Agent 的目标文本
 */
public record AgentSkill(String name, String os, String description, String prompt) {

    /** 技能是否适用于指定操作系统（all 通用）。 */
    public boolean matchesOs(String target) {
        if (os == null) {
            return true;
        }
        return "all".equalsIgnoreCase(os) || os.equalsIgnoreCase(target);
    }
}
