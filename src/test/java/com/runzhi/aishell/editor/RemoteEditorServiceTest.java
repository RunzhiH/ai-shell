package com.runzhi.aishell.editor;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import com.runzhi.aishell.ssh.FileTransferService;
import com.runzhi.aishell.ssh.SSHService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RemoteEditorService 单测（方案 §9）：fake SSHService 覆盖
 * 大小/权限读取、超限拒绝、mtime 冲突、写失败保留 dirty、只读。
 */
class RemoteEditorServiceTest {

    private static final class FakeRemote {
        final AtomicLong mtime = new AtomicLong(1_000_000L);
        final AtomicLong size = new AtomicLong(-1);
        final AtomicReference<byte[]> content = new AtomicReference<>(new byte[0]);
        final AtomicReference<String> writtenPath = new AtomicReference<>();
        final AtomicReference<byte[]> writtenBytes = new AtomicReference<>();
        boolean writable = true;
        boolean directory = false;
        boolean symlink = false;
        int writeFailures = 0;

        SSHService service() {
            return (SSHService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SSHService.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("statForTab")) {
                            long size = this.size.get() < 0 ? content.get().length : this.size.get();
                            return new FileTransferService.RemoteFileStat(size, mtime.get(),
                                    writable ? 0644 : 0444, directory, symlink);
                        }
                        if (name.equals("readBytesForTab")) {
                            int max = (int) args[2];
                            if (content.get().length > max) throw new IllegalArgumentException("exceeds limit");
                            return content.get();
                        }
                        if (name.equals("writeBytesForTab")) {
                            long expected = ((Number) args[3]).longValue();
                            if (expected > 0 && expected != mtime.get()) {
                                throw new FileTransferService.RemoteFileChangedException(expected, mtime.get());
                            }
                            if (writeFailures > 0) {
                                writeFailures--;
                                throw new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "denied");
                            }
                            writtenPath.set((String) args[1]);
                            writtenBytes.set((byte[]) args[2]);
                            content.set((byte[]) args[2]);
                            mtime.set(mtime.get() + 60);
                            return null;
                        }
                        return null;
                    });
        }
    }

    @Test
    void openDecodesContentAndKeepsConflictBaseline() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set("hello\r\nworld".getBytes(StandardCharsets.UTF_8));
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/etc/a.txt");

        assertEquals("tab-1", doc.sessionId());
        assertEquals("/etc/a.txt", doc.remotePath());
        assertEquals(StandardCharsets.UTF_8.name(), doc.encoding());
        assertEquals(TextCodec.Eol.CRLF, doc.eol());
        assertEquals("hello\r\nworld", new String(doc.loadedBytes(), StandardCharsets.UTF_8));
        assertEquals(1_000_000L, doc.loadedMtime());
        assertEquals(12, doc.loadedSize());
        assertFalse(doc.readOnly());
        assertFalse(doc.binary());
    }

    @Test
    void openRefusesTooLargeFilesWithActualSize() {
        FakeRemote remote = new FakeRemote();
        remote.size.set(10L * 1024 * 1024);
        int saved = EditorPreferences.maxInlineBytes();
        try {
            EditorPreferences.setMaxInlineBytes(1024);
            RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
            EditorService.EditorOpenException failure = assertThrows(
                    EditorService.EditorOpenException.class, () -> service.open("/big.log"));
            assertEquals(EditorService.OpenFailure.TOO_LARGE, failure.reason());
            assertEquals(10L * 1024 * 1024, failure.size());
        } finally {
            EditorPreferences.setMaxInlineBytes(saved);
        }
    }

    @Test
    void openRejectsDirectories() {
        FakeRemote remote = new FakeRemote();
        remote.directory = true;
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorService.EditorOpenException failure = assertThrows(
                EditorService.EditorOpenException.class, () -> service.open("/dir"));
        assertEquals(EditorService.OpenFailure.DIRECTORY, failure.reason());
    }

    @Test
    void openMarksReadOnlyWhenWritePermissionMissing() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set("x".getBytes(StandardCharsets.UTF_8));
        remote.writable = false;
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/ro.txt");
        assertTrue(doc.readOnly());
    }

    @Test
    void openMarksBinaryFilesReadOnly() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set(new byte[]{'a', 0, 'b'});
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/bin.dat");
        assertTrue(doc.binary());
        assertTrue(doc.readOnly());
    }

    @Test
    void saveFailsOnMtimeConflict() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set("v1".getBytes(StandardCharsets.UTF_8));
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/a.txt");

        remote.mtime.set(doc.loadedMtime() + 1);
        byte[] bytes = "v2".getBytes(StandardCharsets.UTF_8);
        assertThrows(FileTransferService.RemoteFileChangedException.class,
                () -> service.save(doc, bytes, false));
        assertNull(remote.writtenBytes.get());
    }

    @Test
    void forceSaveIgnoresConflictAndUpdatesBaseline() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set("v1".getBytes(StandardCharsets.UTF_8));
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/a.txt");

        remote.mtime.set(doc.loadedMtime() + 1);
        byte[] bytes = "v2".getBytes(StandardCharsets.UTF_8);
        service.save(doc, bytes, true);
        assertArrayEquals(bytes, remote.writtenBytes.get());
        assertEquals("/a.txt", remote.writtenPath.get());
        assertEquals(remote.mtime.get(), doc.loadedMtime());
        assertEquals(bytes.length, doc.loadedSize());
    }

    @Test
    void saveRefusesReadOnlyDocuments() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set("x".getBytes(StandardCharsets.UTF_8));
        remote.writable = false;
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/ro.txt");
        assertThrows(IllegalStateException.class,
                () -> service.save(doc, "y".getBytes(StandardCharsets.UTF_8), false));
    }

    @Test
    void writeFailureSurfacesSftpPermissionDenied() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set("v1".getBytes(StandardCharsets.UTF_8));
        remote.writeFailures = 1;
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/a.txt");
        SftpException failure = assertThrows(SftpException.class,
                () -> service.save(doc, "v2".getBytes(StandardCharsets.UTF_8), false));
        assertEquals(ChannelSftp.SSH_FX_PERMISSION_DENIED, failure.id);
    }

    @Test
    void reloadReopensCurrentContent() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set("v1".getBytes(StandardCharsets.UTF_8));
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/a.txt");

        remote.content.set("v2-new".getBytes(StandardCharsets.UTF_8));
        remote.mtime.set(2_000_000L);
        EditorDocument fresh = service.reload(doc);
        assertEquals("v2-new", new String(fresh.loadedBytes(), StandardCharsets.UTF_8));
        assertEquals(2_000_000L, fresh.loadedMtime());
        assertFalse(fresh.dirty());
    }

    @Test
    void openReadOnlyBypassesInlineLimit() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set(new byte[4096]);
        int saved = EditorPreferences.maxInlineBytes();
        try {
            EditorPreferences.setMaxInlineBytes(1024);
            RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
            assertThrows(EditorService.EditorOpenException.class, () -> service.open("/big.log"));
            EditorDocument doc = service.openReadOnly("/big.log");
            assertTrue(doc.readOnly());
            assertEquals(4096, doc.loadedSize());
        } finally {
            EditorPreferences.setMaxInlineBytes(saved);
        }
    }

    @Test
    void sizeChangeWithoutMtimeUpdateIsAlsoDetectedAsConflict() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.content.set("v1".getBytes(StandardCharsets.UTF_8));
        RemoteEditorService service = new RemoteEditorService(remote.service(), "tab-1");
        EditorDocument doc = service.open("/a.txt");

        // mtime 精度不足时由 size 辅助校验：只改内容不改 mtime
        remote.content.set("v1-but-longer-content".getBytes(StandardCharsets.UTF_8));
        byte[] bytes = "v2".getBytes(StandardCharsets.UTF_8);
        assertThrows(FileTransferService.RemoteFileChangedException.class,
                () -> service.save(doc, bytes, false));
    }
}
