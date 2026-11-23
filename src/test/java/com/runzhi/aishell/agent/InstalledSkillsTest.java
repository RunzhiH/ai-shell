package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InstalledSkillsTest {
    @TempDir Path directory;

    @Test
    void installsLocalDirectoryWithSpacesAndReloadsTheCatalog() throws Exception {
        Path source = Files.createDirectory(directory.resolve("my skill"));
        Files.writeString(source.resolve("SKILL.md"), document("test-skill", "windows"));
        Path catalog = directory.resolve("user-skills").resolve("catalog.json");
        InstalledSkills installer = new InstalledSkills(catalog);
        List<AgentSkill> imported = installer.install("\"" + source + "\"", Set.of());
        assertEquals(List.of(new AgentSkill("test-skill", "windows", "Example skill",
                "# Instructions\n\nInspect the local environment.")), imported);
        assertEquals(imported, installer.all());
        assertTrue(imported.getFirst().matchesOs("windows"));
        assertFalse(imported.getFirst().matchesOs("linux"));

        InstalledSkills restarted = new InstalledSkills(catalog);
        restarted.load();
        assertEquals(imported, restarted.all());
        assertThrows(IOException.class, () -> restarted.install(source.toString(), Set.of()));
        assertEquals(imported, restarted.all());
    }

    @Test
    void invalidOrReservedSkillsLeaveTheCatalogUntouched() throws Exception {
        Path source = Files.createDirectory(directory.resolve("duplicate"));
        Path markdown = source.resolve("SKILL.md");
        Path catalog = directory.resolve("catalog.json");
        InstalledSkills installer = new InstalledSkills(catalog);
        Files.writeString(markdown, document("resource-check", "all"));
        assertThrows(IOException.class, () -> installer.install(source.toString(), Set.of("resource-check")));
        Files.writeString(markdown, document("bad-os", "unknown"));
        assertThrows(IOException.class, () -> installer.install(source.toString(), Set.of()));
        assertThrows(IOException.class, () -> installer.install("https://example.com/owner/repo", Set.of()));
        assertFalse(Files.exists(catalog));
        assertTrue(installer.all().isEmpty());
    }

    @Test
    void readsNestedGithubSkillsWithoutExtractingOtherFiles() throws Exception {
        Path archive = directory.resolve("repository.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addEntry(zip, "owner-repository/skills/example/SKILL.md", document("example", "linux"));
            addEntry(zip, "owner-repository/skills/example/scripts/setup.sh", "#!/bin/sh\n");
            addEntry(zip, "owner-repository/SKILL.md", document("root-skill", "all"));
        }
        assertEquals(List.of("example", "root-skill"), InstalledSkills.readArchive(archive)
                .stream().map(AgentSkill::name).toList());
        assertFalse(Files.exists(directory.resolve("owner-repository")));
    }

    @Test
    void installsFromPublicGithubWhenRequested() throws Exception {
        String url = System.getProperty("aishell.test.skillRepository");
        assumeTrue(url != null, "set aishell.test.skillRepository to exercise a live public GitHub repository");
        Path catalog = directory.resolve("catalog.json");
        InstalledSkills installer = new InstalledSkills(catalog);
        List<AgentSkill> installed = installer.install(url, Set.of());
        assertFalse(installed.isEmpty());
        assertTrue(Files.isRegularFile(catalog));
        assertEquals(installed, installer.all());
    }

    private static String document(String name, String os) {
        return """
                ---
                name: %s
                description: >-
                  Example skill
                metadata:
                  os: %s
                ---
                # Instructions

                Inspect the local environment.
                """.formatted(name, os);
    }

    private static void addEntry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
