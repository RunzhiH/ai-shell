package com.runzhi.aishell.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransferQueueStoreTest {
    @TempDir Path tempDir;

    @Test
    void cancelledPendingJobCannotStartAndCannotCancelAnotherJob() {
        TransferQueueStore store = new TransferQueueStore(tempDir.resolve("cancel.json"));
        var first = store.enqueue(TransferQueueStore.Direction.UPLOAD, "server", 22, "root", List.of("a"), "/fixed", "SKIP");
        var second = store.enqueue(TransferQueueStore.Direction.UPLOAD, "server", 22, "root", List.of("b"), "/other", "SKIP");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        store.registerCancel(second.id(), calls::incrementAndGet);
        store.cancel(first.id());
        assertFalse(store.startJob(first.id()));
        assertEquals(0, calls.get());
        assertTrue(store.startJob(second.id()));
        assertFalse(store.startJob(second.id()));
        assertEquals("/fixed", store.snapshot().stream().filter(j -> j.id().equals(first.id())).findFirst().orElseThrow().destination());
    }

    @Test
    void failedJobsCanBeRemovedAndProgressDoesNotChangeEndpoints() throws Exception {
        TransferQueueStore store = new TransferQueueStore(tempDir.resolve("progress.json"));
        var job = store.enqueue(TransferQueueStore.Direction.DOWNLOAD, "server", 2222, "root", List.of("/logs/a"), "C:/fixed", "SKIP");
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable observer = store.observe(changes::incrementAndGet);
        assertTrue(store.startJob(job.id()));
        store.progress(job.id(), "68%");
        assertEquals("68%", store.progress(job.id()));
        store.removeFailed(job.id());
        assertEquals(1, store.snapshot().size());
        store.markFailed(job.id());
        assertEquals("C:/fixed", store.snapshot().getFirst().destination());
        store.removeFailed(job.id());
        assertTrue(store.snapshot().isEmpty());
        assertTrue(changes.get() >= 3);
        observer.close();
    }

    @Test
    void recoverySeparatesPortsAndRetainsIdentityAfterReload() {
        Path file = tempDir.resolve("ports.json");
        TransferQueueStore store = new TransferQueueStore(file);
        var ssh = store.enqueue(TransferQueueStore.Direction.UPLOAD, "server", 22, "root",
                List.of("a"), "/data", "SKIP");
        var alternate = store.enqueue(TransferQueueStore.Direction.DOWNLOAD, "server", 2222, "root",
                List.of("b"), "/local", "RESUME");
        store.markRunning(alternate.id());
        TransferQueueStore.resetSharedInstancesForTest();
        TransferQueueStore loaded = new TransferQueueStore(file);
        assertEquals(List.of(ssh.id()), loaded.recoverable("server", 22, "root").stream().map(TransferQueueStore.Job::id).toList());
        assertEquals(List.of(alternate.id()), loaded.recoverable("server", 2222, "root").stream().map(TransferQueueStore.Job::id).toList());
        assertEquals(TransferQueueStore.Status.INTERRUPTED, loaded.recoverable("server", 2222, "root").getFirst().status());
        assertTrue(loaded.recoverable("server", 22, "other-user").isEmpty());
    }

    @Test
    void legacyRecordWithoutPortCannotMatchVerifiedEndpoint() throws Exception {
        Path file = tempDir.resolve("legacy.json");
        java.nio.file.Files.writeString(file, """
                [{"id":"legacy","direction":"UPLOAD","host":"server","username":"root",
                  "sources":["a"],"destination":"/tmp","policy":"RESUME","status":"INTERRUPTED"}]
                """);
        TransferQueueStore store = new TransferQueueStore(file);
        assertTrue(store.recoverable("server", 22, "root").isEmpty());
        assertTrue(store.recoverable("server", 2222, "root").isEmpty());
        assertEquals(0, store.recoverable("server", "root").getFirst().port());
    }

    @Test
    void explicitEndpointRejectsUnknownPort() {
        TransferQueueStore store = new TransferQueueStore(tempDir.resolve("invalid.json"));
        assertThrows(IllegalArgumentException.class, () -> store.enqueue(
                TransferQueueStore.Direction.UPLOAD, "server", 0, "root", List.of("a"), "/tmp", "RESUME"));
    }

    @Test
    void persistsAndRecoversInterruptedJobsWithoutCredentials() throws Exception {
        Path file = tempDir.resolve("queue.json");
        TransferQueueStore store = new TransferQueueStore(file);
        var job = store.enqueue(TransferQueueStore.Direction.UPLOAD, "server", "user",
                List.of("C:/data/a.txt"), "/tmp", "RESUME");
        store.markRunning(job.id());

        // 模拟进程崩溃后重启：清空共享实例注册表，重新加载时断电中的 RUNNING 任务迁移为 INTERRUPTED
        TransferQueueStore.resetSharedInstancesForTest();
        TransferQueueStore reloaded = new TransferQueueStore(file);
        var jobs = reloaded.recoverable("server", "user");
        assertEquals(1, jobs.size());
        assertEquals(TransferQueueStore.Status.INTERRUPTED, jobs.getFirst().status());
        assertFalse(java.nio.file.Files.readString(file).contains("password"));

        reloaded.complete(job.id());
        assertTrue(new TransferQueueStore(file).recoverable("server", "user").isEmpty());
    }
}
