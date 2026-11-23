/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 内置技能库：从 classpath 资源 agent-skills.json 加载，按操作系统分类提供常用技能。
 * <p>
 * 加载失败时退化为空列表：技能是增强项，不得阻断 Agent 主流程。
 */
@Slf4j
public final class SkillLibrary {

    private static final String RESOURCE = "/agent-skills.json";

    private static volatile List<AgentSkill> cached;

    private SkillLibrary() {
        // 工具类，禁止实例化
    }

    /** 全部技能（按资源文件顺序）。 */
    public static List<AgentSkill> all() {
        List<AgentSkill> snapshot = cached;
        if (snapshot == null) {
            synchronized (SkillLibrary.class) {
                snapshot = cached;
                if (snapshot == null) {
                    snapshot = load();
                    cached = snapshot;
                }
            }
        }
        return snapshot;
    }

    /** 适用于指定操作系统的技能（os=all 的通用技能一并返回）；传 all/空 返回整库。 */
    public static List<AgentSkill> forOs(String os) {
        String target = normalize(os);
        if (target.isEmpty() || "all".equals(target)) {
            return all();
        }
        return all().stream()
                .filter(skill -> skill.matchesOs(target))
                .toList();
    }

    /** 按名称查找技能（名称不区分大小写）。 */
    public static Optional<AgentSkill> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(skill -> skill.name() != null
                        && skill.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }

    /** /skills 自动补全用：所有技能名。 */
    public static List<String> names() {
        return all().stream().map(AgentSkill::name).toList();
    }

    private static String normalize(String os) {
        return os == null ? "" : os.trim().toLowerCase(Locale.ROOT);
    }

    private static List<AgentSkill> load() {
        List<AgentSkill> skills = new ArrayList<>();
        try (InputStream stream = SkillLibrary.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                log.warn("技能库资源不存在: {}", RESOURCE);
                return List.of();
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            json.getAsJsonArray("skills").forEach(element -> {
                JsonObject item = element.getAsJsonObject();
                skills.add(new AgentSkill(
                        item.get("name").getAsString(),
                        item.has("os") ? item.get("os").getAsString() : "all",
                        item.has("description") ? item.get("description").getAsString() : "",
                        item.get("prompt").getAsString()));
            });
            log.info("加载 {} 个内置技能", skills.size());
        } catch (Exception e) {
            log.warn("加载技能库失败，技能命令将不可用: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(skills);
    }

    /** 仅供测试重置缓存。 */
    static void resetForTest() {
        cached = null;
    }
}
