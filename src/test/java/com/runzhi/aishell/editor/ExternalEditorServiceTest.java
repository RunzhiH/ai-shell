package com.runzhi.aishell.editor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部编辑器单测（方案 §9）：命令模板替换、临时路径哈希、哈希比对、清理策略。
 * 不真实启动第三方编辑器进程。
 */
class ExternalEditorServiceTest {

    @Test
    void buildCommandReplacesFilePlaceholderWithoutShell() {
        ExternalEditor editor = new ExternalEditor("code", List.of("code", "--wait", "{file}"),
                ExternalEditor.Source.VSCODE);
        List<String> command = editor.buildCommand("C:/tmp/a b.txt");
        assertEquals(List.of("code", "--wait", "C:/tmp/a b.txt"), command);
    }

    @Test
    void customCommandTokensRespectQuotes() {
        List<String> tokens = ExternalEditorDetector.tokenize('"' + "C:/Program Files/code.exe" + '"' + " {file}");
        assertEquals(2, tokens.size());
        assertEquals("C:/Program Files/code.exe", tokens.get(0));
        assertEquals("{file}", tokens.get(1));
    }

    @Test
    void templateWithoutPlaceholderDoesNotBreakBuild() {
        ExternalEditor editor = new ExternalEditor("odd", List.of("odd"), ExternalEditor.Source.CUSTOM);
        List<String> command = editor.buildCommand("a.txt");
        assertEquals(List.of("odd"), command);
    }

    @Test
    void systemDefaultIsAlwaysAvailable() {
        List<ExternalEditor> editors = ExternalEditorDetector.detectAvailable();
        assertFalse(editors.isEmpty());
        assertNotNull(ExternalEditorDetector.preferred());
    }

    @Test
    void customCommandTakesPrecedenceWhenConfigured() {
        String saved = EditorPreferences.externalCommand();
        try {
            EditorPreferences.setExternalCommand("\"C:/Tools/hx.exe\" {file}");
            List<ExternalEditor> editors = ExternalEditorDetector.detectAvailable();
            assertEquals(ExternalEditor.Source.CUSTOM, editors.get(0).source());
            assertEquals("hx (custom)", editors.get(0).name());
            List<String> command = editors.get(0).buildCommand("f.txt");
            assertEquals("C:/Tools/hx.exe", command.get(0));
            assertEquals("f.txt", command.get(1));
        } finally {
            EditorPreferences.setExternalCommand(saved);
        }
    }

    @Test
    void sha256MatchesKnownValue() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                ExternalEditorService.sha256(data));
    }

    @Test
    void sha256DistinguishesDifferentBytes() {
        byte[] a = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] b = "hello!".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(ExternalEditorService.sha256(a), ExternalEditorService.sha256(b));
    }

    @Test
    void tempPathIsolatedPerFileBySha1() {
        String nginx = ExternalEditorService.sha1Hex("/etc/nginx/nginx.conf");
        String other = ExternalEditorService.sha1Hex("/etc/nginx/nginx.conf ");
        assertEquals(40, nginx.length());
        assertNotEquals(nginx, other);
        assertEquals("nginx.conf", ExternalEditorService.fileNameOf("/etc/nginx/nginx.conf"));
        assertEquals("/etc/nginx/nginx.conf", ExternalEditorService.sessionKey("/etc/nginx/nginx.conf"));
    }

    @Test
    void staleTempDirsAreCleanedBeyondRetention() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "aishell-edit");
        Files.createDirectories(root);
        Path stale = Files.createDirectories(root.resolve("unit-test-stale-" + System.nanoTime()));
        Files.write(stale.resolve("a.txt"), "stale".getBytes(StandardCharsets.UTF_8));
        FileTime old = FileTime.from(Instant.now().minusSeconds(30L * 24 * 3600));
        Files.setLastModifiedTime(stale, old);

        Path fresh = Files.createDirectories(root.resolve("unit-test-fresh-" + System.nanoTime()));
        Files.write(fresh.resolve("b.txt"), "fresh".getBytes(StandardCharsets.UTF_8));

        ExternalEditorService.cleanupStaleTempDirs();

        assertFalse(Files.exists(stale), "超期残留目录应被清理");
        assertTrue(Files.exists(fresh), "保留期内的目录不应被清理");
    }

    @Test
    void sha256HandlesEmptyData() {
        assertEquals(ExternalEditorService.sha256(new byte[0]),
                ExternalEditorService.sha256(new byte[0]));
    }
}
