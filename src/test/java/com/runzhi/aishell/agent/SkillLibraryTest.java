package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内置技能库回归（GitHub #6）：按操作系统分类加载、按名称查找。
 */
class SkillLibraryTest {

    @Test
    void builtinSkillsAreLoaded() {
        List<AgentSkill> all = SkillLibrary.all();
        assertFalse(all.isEmpty(), "内置技能库不应为空");
        for (AgentSkill skill : all) {
            assertNotNull(skill.name());
            assertNotNull(skill.description());
            assertNotNull(skill.prompt());
            assertFalse(skill.prompt().isBlank(), "技能提示词不得为空");
        }
        Set<String> names = new HashSet<>();
        for (AgentSkill skill : all) {
            assertTrue(names.add(skill.name()), "技能名不得重复: " + skill.name());
        }
    }

    @Test
    void linuxSkillsContainTheCommonOpsTemplates() {
        List<AgentSkill> linux = SkillLibrary.forOs("linux");
        assertTrue(linux.stream().anyMatch(skill -> skill.name().equals("resource-check")));
        assertTrue(linux.stream().anyMatch(skill -> skill.name().equals("security-audit")));
        // 通用技能（os=all）随任意系统出现
        assertTrue(linux.stream().anyMatch(skill -> skill.name().equals("cache-cleanup")));
        // 其他系统的专属技能不应混入
        assertFalse(linux.stream().anyMatch(skill -> skill.name().equals("brew-update")));
        assertFalse(linux.stream().anyMatch(skill -> skill.name().equals("win-service")));
    }

    @Test
    void perOsFiltersIncludeCommonAndExclusiveSkills() {
        List<AgentSkill> macos = SkillLibrary.forOs("macos");
        assertTrue(macos.stream().anyMatch(skill -> skill.name().equals("brew-update")));
        assertTrue(macos.stream().anyMatch(skill -> skill.name().equals("cache-cleanup")));

        List<AgentSkill> windows = SkillLibrary.forOs("windows");
        assertTrue(windows.stream().anyMatch(skill -> skill.name().equals("win-service")));
        assertFalse(windows.stream().anyMatch(skill -> skill.name().equals("brew-update")));

        List<AgentSkill> all = SkillLibrary.forOs("all");
        assertEquals(SkillLibrary.all().size(), all.size(), "all 应返回整库");
    }

    @Test
    void findIsCaseInsensitiveAndRejectsUnknownNames() {
        assertTrue(SkillLibrary.find("resource-check").isPresent());
        assertTrue(SkillLibrary.find("Resource-Check").isPresent());
        assertTrue(SkillLibrary.find(" docker-check ").isPresent());
        assertFalse(SkillLibrary.find("nope").isPresent());
        assertFalse(SkillLibrary.find(null).isPresent());
    }

    @Test
    void namesAreExposedForCompletion() {
        List<String> names = SkillLibrary.names();
        assertEquals(SkillLibrary.all().size(), names.size());
        assertTrue(names.contains("resource-check"));
    }
}
