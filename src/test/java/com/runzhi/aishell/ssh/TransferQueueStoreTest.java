package com.runzhi.aishell.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransferQueueStoreTest {
    @TempDir Path tempDir;

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
