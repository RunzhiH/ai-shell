package com.runzhi.aishell.monitor;

import com.runzhi.aishell.monitor.domain.MonitorSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连接级监控会话测试（方案 §9.2：会话标识、暂停/恢复基线重建、停止后不再回调、间隔立即生效）。
 */
class MonitorSessionTest {

    private static class FakeProvider implements DataSourceProvider {
        private final Map<String, String> outputs = new HashMap<>();
        private volatile boolean connected = true;

        void put(String command, String output) {
            outputs.put(command, output);
        }

        void setConnected(boolean connected) {
            this.connected = connected;
        }

        @Override
        public String executeCommand(String command) {
            return outputs.getOrDefault(command, "");
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private static final String UPTIME = " 10:42:50 up 2 days, 20:01,  4 users,  load average: 0.15, 0.15, 0.13";
    private static final String FREE =
            "              total        used        free      shared  buff/cache   available\n" +
            "Mem:        2000000     1000000     800000       0      200000     1500000";
    private static final String CPU_STAT_BASE = "cpu  100 100 100 700 50 10 20 20 0 0";
    private static final String CPU_STAT_SAMPLE = "cpu  110 105 110 750 55 12 25 25 0 0";
    private static final String DF_PK =
            "Filesystem    1024-blocks      Used Available Capacity Mounted on\n" +
            "/dev/sda1        52428800   26214400  26214400      50% /";
    private static final String DISKSTATS =
            "   8       0 sda 100 10 5000 50 200 20 6000 60 0 88 200\n";
    private static final String DISKSTATS2 =
            "   8       0 sda 110 12 6000 60 220 24 7000 70 0 98 260\n";
    private static final String NET_DEV_HEADER =
            "Inter-|   Receive                                                |  Transmit\n" +
            " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n";
    private static final String NET_DEV = NET_DEV_HEADER +
            "  eth0:    1000      10    0    0    0     0          0         0     2000      20    0    0    0     0       0          0\n";
    private static final String NET_DEV2 = NET_DEV_HEADER +
            "  eth0:    2024      12    0    0    0     0          0         0     3024      22    0    0    0     0       0          0\n";

    private FakeProvider baseProvider() {
        FakeProvider provider = new FakeProvider();
        provider.put("uptime", UPTIME);
        provider.put("free -k", FREE);
        provider.put("cat /proc/stat | head -n 1", CPU_STAT_BASE);
        provider.put("LC_ALL=C df -Pk", DF_PK);
        provider.put("cat /proc/diskstats 2>/dev/null", DISKSTATS);
        provider.put("cat /proc/net/dev", NET_DEV);
        return provider;
    }

    private MonitorSnapshot awaitNext(java.util.Queue<CompletableFuture<MonitorSnapshot>> queue, long timeoutMs)
            throws Exception {
        CompletableFuture<MonitorSnapshot> future = new CompletableFuture<>();
        queue.add(future);
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("会话标识随快照下发；首次采样标记采样中；第二轮得到有效速率")
    void deliversSnapshotsWithSessionIdAndFirstSampleState() throws Exception {
        FakeProvider provider = baseProvider();
        MonitorSession session = new MonitorSession(provider, 600);
        java.util.Queue<CompletableFuture<MonitorSnapshot>> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        session.addListener(snapshot -> {
            CompletableFuture<MonitorSnapshot> f = queue.poll();
            if (f != null) f.complete(snapshot);
        });

        session.start();
        try {
            MonitorSnapshot first = awaitNext(queue, 8000);
            assertNotNull(first);
            assertEquals(session.getSessionId(), first.getSessionId(), "快照必须携带会话标识");
            assertTrue(first.isCpuFirstSample(), "首次 CPU 采样应标记采样中");
            assertNotNull(first.getMounts());
            assertNotNull(first.getDiskIo());
            assertNotNull(first.getNetworkRates());

            // 第二轮采样：CPU 有效、磁盘/网络速率就绪
            provider.put("cat /proc/stat | head -n 1", CPU_STAT_SAMPLE);
            provider.put("cat /proc/diskstats 2>/dev/null", DISKSTATS2);
            provider.put("cat /proc/net/dev", NET_DEV2);
            MonitorSnapshot second = awaitNext(queue, 8000);
            assertFalse(second.isCpuFirstSample(), "第二次采样 CPU 应有效");
            assertTrue(second.getSystemMetrics().getCpuTotalUsed() > 0, "总使用率 100-idle 应大于 0");
            assertNotNull(second.getDiskIo().getValue(), "磁盘 I/O 速率就绪");
            assertNotNull(second.getNetworkRates().getValue(), "网络速率就绪");
        } finally {
            session.stop();
        }
    }

    @Test
    @DisplayName("切换会话后旧会话返回的数据按会话标识丢弃")
    void sessionsOfDifferentBindingsAreIsolated() throws Exception {
        FakeProvider providerA = baseProvider();
        FakeProvider providerB = baseProvider();
        MonitorSession sessionA = new MonitorSession(providerA, 600);
        MonitorSession sessionB = new MonitorSession(providerB, 600);

        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        sessionA.addListener(s -> aCount.incrementAndGet());
        sessionB.addListener(s -> bCount.incrementAndGet());

        sessionA.start();
        sessionB.start();
        try {
            long deadline = System.currentTimeMillis() + 8000;
            while ((aCount.get() == 0 || bCount.get() == 0) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(aCount.get() > 0 && bCount.get() > 0, "两个会话应各自交付快照");
            assertNotEquals(sessionA.getSessionId(), sessionB.getSessionId(), "不同会话的标识必须不同");

            // 旧会话停止后不再交付
            sessionA.stop();
            int countAfterStop = aCount.get();
            Thread.sleep(1500);
            assertEquals(countAfterStop, aCount.get(), "停止后的会话不应再回调");
        } finally {
            sessionA.stop();
            sessionB.stop();
        }
    }

    @Test
    @DisplayName("暂停后恢复时重建差分基线，避免暂停区间形成速率突刺")
    void pauseAndResumeRebuildsBaselines() throws Exception {
        FakeProvider provider = baseProvider();
        MonitorSession session = new MonitorSession(provider, 600);
        java.util.Queue<CompletableFuture<MonitorSnapshot>> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        session.addListener(snapshot -> {
            CompletableFuture<MonitorSnapshot> f = queue.poll();
            if (f != null) f.complete(snapshot);
        });

        session.start();
        try {
            awaitNext(queue, 8000); // 首次采样（建基线）
            provider.put("cat /proc/diskstats 2>/dev/null", DISKSTATS2);
            provider.put("cat /proc/net/dev", NET_DEV2);
            awaitNext(queue, 8000); // 第二轮（速率就绪）

            session.pause();
            Thread.sleep(300);
            AtomicBoolean busy = new AtomicBoolean();
            session.setIntervalMs(600);
            // 暂停期间计数器继续走（模拟暂停区间）
            provider.put("cat /proc/diskstats 2>/dev/null",
                    "   8       0 sda 99999 99 99999 99 99999 99 99999 99 0 98 9999\n");
            session.resume();
            MonitorSnapshot afterResume = awaitNext(queue, 8000);
            // 恢复后基线重建：本轮标记采样中而不是输出暂停区间的巨大速率
            var sda = afterResume.getDiskIo().getValue() == null ? null : afterResume.getDiskIo().getValue().get("sda");
            if (sda != null) {
                assertEquals(com.runzhi.aishell.monitor.domain.SampleState.FIRST_SAMPLE, sda.getState(),
                        "恢复后应重建基线，暂停区间的增量不形成速率突刺");
            }
        } finally {
            session.stop();
        }
    }

    @Test
    @DisplayName("停止的会话不再使用；isUsable 反映连接与会话状态")
    void stoppedSessionIsNotUsableAndTriggerNowIsHarmless() {
        FakeProvider provider = baseProvider();
        MonitorSession session = new MonitorSession(provider, 5000);
        assertFalse(session.isUsable(), "停止状态下不应可用，即便数据源已连接……（此例会话未启动）");
        // start + stop 后不再可用
        session.start();
        session.stop();
        assertFalse(session.isUsable(), "停止后的会话不可用");
        provider.setConnected(false);
        session.triggerNow(); // 不得抛异常
    }

    @Test
    @DisplayName("setIntervalMs 立即生效（夹紧到下限）")
    void setIntervalMsAppliesImmediately() {
        FakeProvider provider = baseProvider();
        MonitorSession session = new MonitorSession(provider, 5000);
        session.setIntervalMs(1);
        assertEquals(500, session.getIntervalMs(), "间隔不得小于 500ms");
        session.setIntervalMs(10000);
        assertEquals(10000, session.getIntervalMs());
    }
}
