/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Imports SKILL.md instructions and publishes them only after a complete catalog write. */
final class InstalledSkills {
    private static final String DOCUMENT = "SKILL.md";
    private static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    private static final int MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_SKILLS = 100;
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern REPOSITORY = Pattern.compile(
            "(?i)https://github\\.com/([a-z0-9-]+)/([a-z0-9_.-]+?)(?:\\.git)?/?");
    private static final Pattern FRONTMATTER = Pattern.compile("\\A\\uFEFF?---\\h*\\R(.*?)^---\\h*\\R(.*)\\z",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Gson JSON = new Gson();

    /** Catalog owned by the current AIShell user. */
    private final Path catalog;
    /** Immutable snapshot exposed to the Agent's completion and lookup paths. */
    private volatile List<AgentSkill> skills = List.of();

    InstalledSkills(Path catalog) {
        this.catalog = catalog;
    }

    /** Loads the most recent catalog written by the installer. */
    void load() throws IOException {
        if (!Files.exists(catalog)) return;
        try (var reader = Files.newBufferedReader(catalog)) {
            skills = List.of(JSON.fromJson(reader, AgentSkill[].class));
        } catch (JsonParseException | NullPointerException invalid) {
            throw new IOException("Invalid installed skills catalog", invalid);
        }
    }

    List<AgentSkill> all() {
        return skills;
    }

    /** Imports one local skill directory or all skill documents in a public repository. */
    List<AgentSkill> install(String source, Set<String> reserved) throws IOException {
        List<AgentSkill> incoming;
        String path = source.strip();
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() > 1) {
            path = path.substring(1, path.length() - 1);
        }
        if (path.isBlank()) throw new IOException("A skill directory or repository URL is required");
        if (path.contains("://")) {
            incoming = download(path);
        } else {
            Path document = Path.of(path).resolve(DOCUMENT);
            if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing SKILL.md in the selected directory");
            }
            try (InputStream stream = Files.newInputStream(document)) {
                incoming = List.of(parse(readDocument(stream)));
            }
        }
        return publish(incoming, reserved);
    }

    /** Writes a whole batch, rejecting name collisions before changing the catalog. */
    private synchronized List<AgentSkill> publish(List<AgentSkill> incoming, Set<String> reserved) throws IOException {
        if (incoming.isEmpty()) throw new IOException("No SKILL.md found in the repository");
        load();
        Set<String> names = new HashSet<>(reserved);
        skills.forEach(skill -> names.add(skill.name()));
        for (AgentSkill skill : incoming) {
            if (!names.add(skill.name())) throw new IOException("Skill name already exists: " + skill.name());
        }
        List<AgentSkill> updated = new ArrayList<>(skills);
        updated.addAll(incoming);
        if (updated.size() > MAX_SKILLS) throw new IOException("Installed skill limit exceeded (100)");

        Files.createDirectories(catalog.getParent());
        Path temporary = Files.createTempFile(catalog.getParent(), "skill-catalog-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(updated));
            Files.move(temporary, catalog, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            skills = List.copyOf(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return List.copyOf(incoming);
    }

    /** Downloads the default branch without requiring Git or GitHub credentials. */
    private static List<AgentSkill> download(String source) throws IOException {
        var repository = REPOSITORY.matcher(source);
        if (!repository.matches() || Set.of(".", "..").contains(repository.group(2))) {
            throw new IOException("Expected a public GitHub repository URL (https://github.com/owner/repo)");
        }
        URI archive = URI.create("https://codeload.github.com/" + repository.group(1) + "/"
                + repository.group(2) + "/zip/HEAD");
        var request = HttpRequest.newBuilder(archive).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "AIShell-SkillInstaller").build();
        Path temporary = Files.createTempFile("aishell-skills-", ".zip");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("GitHub archive download failed (HTTP " + response.statusCode() + ")");
                }
                copyArchive(input, temporary);
            }
            return readArchive(temporary);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub archive download interrupted", interrupted);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Bounds both the archive size and the time spent waiting for its response body. */
    private static void copyArchive(InputStream input, Path temporary) throws IOException {
        Thread timeout = Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
                input.close();
            } catch (IOException | InterruptedException ignored) {
                // The download has already ended or the response was closed.
            }
        });
        try (var output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                total += read;
                if (total > MAX_ARCHIVE_BYTES) throw new IOException("GitHub archive exceeds 32 MiB");
                output.write(buffer, 0, read);
            }
        } finally {
            timeout.interrupt();
        }
    }

    /** Reads matching documents directly from the ZIP without extracting repository files. */
    static List<AgentSkill> readArchive(Path archive) throws IOException {
        List<AgentSkill> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith("/" + DOCUMENT)) continue;
                if (result.size() >= MAX_SKILLS) throw new IOException("Repository contains more than 100 skills");
                try (InputStream stream = zip.getInputStream(entry)) {
                    result.add(parse(readDocument(stream)));
                } catch (IOException invalid) {
                    throw new IOException(entry.getName() + ": " + invalid.getMessage(), invalid);
                }
            }
        }
        return result;
    }

    /** Limits the content read from a local file or a compressed archive entry. */
    private static String readDocument(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_DOCUMENT_BYTES + 1);
        if (bytes.length > MAX_DOCUMENT_BYTES) throw new IOException("SKILL.md exceeds 256 KiB");
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** Reads standard YAML frontmatter and retains the Markdown body for the Agent input. */
    private static AgentSkill parse(String document) throws IOException {
        var parts = FRONTMATTER.matcher(document);
        if (!parts.matches()) throw new IOException("SKILL.md requires YAML frontmatter and Markdown instructions");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        try {
            Object yaml = new Yaml(new SafeConstructor(options)).load(parts.group(1));
            if (!(yaml instanceof Map<?, ?> fields)
                    || !(fields.get("name") instanceof String name) || name.length() > 64 || !NAME.matcher(name).matches()
                    || !(fields.get("description") instanceof String description) || description.isBlank()
                    || description.length() > 1024 || parts.group(2).isBlank()) {
                throw new IOException("SKILL.md requires a valid name, description, and instructions");
            }
            Object os = fields.get("os");
            if (os == null && fields.get("metadata") instanceof Map<?, ?> metadata) os = metadata.get("os");
            String target = os == null ? AgentOsPreference.ALL : os.toString().strip().toLowerCase(Locale.ROOT);
            if (!AgentOsPreference.SUPPORTED.contains(target)) {
                throw new IOException("Unsupported skill OS: " + target);
            }
            return new AgentSkill(name, target, description.strip(), parts.group(2).strip());
        } catch (YAMLException invalid) {
            throw new IOException("Invalid SKILL.md YAML frontmatter", invalid);
        }
    }
}
