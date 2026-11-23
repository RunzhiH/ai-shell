package com.runzhi.aishell.monitor;

import com.runzhi.aishell.monitor.domain.MountInfo;
import com.runzhi.aishell.monitor.domain.SampleState;
import com.runzhi.aishell.monitor.sampling.DiskIoCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 磁盘 I/O 采集器测试（方案 §9.1：解析、差分、计数器变化、设备增删）。
 */
class DiskIoCollectorTest {

    private static final String DISKSTATS = DiskIoCollector.DISKSTATS_COMMAND;

    /** 按命令返回预设输出的假数据源。 */
    private static class FakeProvider implements DataSourceProvider {
        private final Map<String, String> outputs = new HashMap<>();

        void put(String command, String output) {
            outputs.put(command, output);
        }

        @Override
        public String executeCommand(String command) {
            return outputs.getOrDefault(command, "");
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    /** 两台设备的完整 /proc/diskstats（含分区）。 */
    private static final String DISKSTATS_OUTPUT =
            "   8       0 sda 1234 56 7890 123 2345 67 8901 234 0 88 350\n" +
            "   8       1 sda1 100 10 5000 50 200 20 6000 60 0 108 108\n" +
            " 253       0 dm-0 500 5 4000 40 600 6 5000 50 0 90 90";

    private DiskIoCollector collectorWith(FakeProvider provider) {
        return new DiskIoCollector(new CommandRunner(provider, new java.util.concurrent.atomic.AtomicBoolean(false)));
    }

    @Test
    @DisplayName("按设备解析完整 /proc/diskstats，不再遗漏 head -n 5 之外的设备")
    void parsesAllDevicesWithoutHeadLimit() {
        FakeProvider provider = new FakeProvider();
        provider.put(DISKSTATS, DISKSTATS_OUTPUT);

        DiskIoCollector collector = collectorWith(provider);

        DiskIoCollector.Result first = collector.collect(List.of());
        assertTrue(first.firstSample, "首次采样只建立基线");

        DiskIoCollector.Result second = collector.collect(List.of());
        assertEquals(3, second.rates.size(), "sda/sda1/dm-0 都应出现");
        assertTrue(second.rates.containsKey("sda"));
        assertTrue(second.rates.containsKey("sda1"));
        assertTrue(second.rates.containsKey("dm-0"));
        // 分区 sda1 解析为分区层级，sda 为物理设备
        assertEquals("物理设备", second.rates.get("sda").getDeviceLevel());
        assertEquals("分区", second.rates.get("sda1").getDeviceLevel());
        assertEquals("逻辑卷", second.rates.get("dm-0").getDeviceLevel());
    }

    @Test
    @DisplayName("相邻两次快照按设备差分计算速率与 IOPS")
    void computesPerDeviceRatesFromAdjacentSnapshots() {
        FakeProvider provider = new FakeProvider();
        provider.put(DISKSTATS,
                "   8       0 sda 100 10 5000 50 200 20 6000 60 0 88 200\n");
        DiskIoCollector collector = collectorWith(provider);
        collector.collect(List.of()); // 建立基线

        provider.put(DISKSTATS,
                "   8       0 sda 110 12 6000 60 220 24 7000 70 0 98 260\n");
        DiskIoCollector.Result result = collector.collect(List.of());

        var rate = result.rates.get("sda");
        assertNotNull(rate);
        assertEquals(SampleState.OK, rate.getState());
        // 扇区增量：读 +1000 扇区，写 +1000 扇区（512 字节/扇区）
        assertTrue(rate.getReadBytesPerSec() > 0, "读取速率应大于 0");
        assertTrue(rate.getWriteBytesPerSec() > 0, "写入速率应大于 0");
        // 读操作 +10 次的 IOPS 应大于 0
        assertTrue(rate.getReadIops() > 0, "读 IOPS 应大于 0");
        assertTrue(rate.getWriteIops() > 0, "写 IOPS 应大于 0");
        // 平均请求耗时依赖操作增量：10+20=30 次操作，加权耗时 260-200=60ms
        assertNotNull(rate.getAvgRequestMs());
        assertEquals(2.0, rate.getAvgRequestMs(), 0.5);
        // 累计读写字节
        assertEquals(6000 * 512, rate.getTotalReadBytes());
        assertEquals(7000 * 512, rate.getTotalWriteBytes());
    }

    @Test
    @DisplayName("没有操作增量时平均请求耗时为 null（界面显示 —）")
    void avgRequestLatencyIsNullWithoutIoDelta() {
        FakeProvider provider = new FakeProvider();
        provider.put(DISKSTATS, "   8       0 sda 100 10 5000 50 200 20 6000 60 0 88 200\n");
        DiskIoCollector collector = collectorWith(provider);
        collector.collect(List.of());

        // 操作计数没变（空闲）
        provider.put(DISKSTATS, "   8       0 sda 100 10 5000 50 200 20 6000 60 0 90 205\n");
        DiskIoCollector.Result result = collector.collect(List.of());

        assertNull(result.rates.get("sda").getAvgRequestMs(), "无操作增量时不给出平均耗时");
    }

    @Test
    @DisplayName("计数器下降（重置）时重建基线，不输出负速率")
    void rebuildsBaselineWhenCounterDecreases() {
        FakeProvider provider = new FakeProvider();
        provider.put(DISKSTATS, "   8       0 sda 100 10 5000 50 200 20 6000 60 0 88 200\n");
        DiskIoCollector collector = collectorWith(provider);
        collector.collect(List.of());

        provider.put(DISKSTATS, "   8       0 sda 5 1 100 5 8 2 200 8 0 10 10\n");
        DiskIoCollector.Result result = collector.collect(List.of());
        var rate = result.rates.get("sda");
        assertEquals(SampleState.FIRST_SAMPLE, rate.getState(), "计数器下降应重建基线");
        assertEquals(0.0, rate.getReadBytesPerSec(), "本轮不输出速率");
        assertTrue(rate.getReadBytesPerSec() >= 0, "不输出负速率");
    }

    @Test
    @DisplayName("设备消失后从结果中移除；重新出现时重建基线")
    void removesDisappearedDeviceAndRebuildsOnReappearance() {
        FakeProvider provider = new FakeProvider();
        provider.put(DISKSTATS,
                "   8       0 sda 100 10 5000 50 200 20 6000 60 0 88 200\n" +
                "   8      16 sdb 10 1 500 5 20 2 600 6 0 9 9\n");
        DiskIoCollector collector = collectorWith(provider);
        collector.collect(List.of());

        provider.put(DISKSTATS, "   8       0 sda 110 12 6000 60 220 24 7000 70 0 98 260\n");
        DiskIoCollector.Result second = collector.collect(List.of());
        assertFalse(second.rates.containsKey("sdb"), "消失的设备不再出现");

        provider.put(DISKSTATS,
                "   8       0 sda 110 12 6000 60 220 24 7000 70 0 98 260\n" +
                "   8      16 sdb 12 2 700 7 30 3 900 9 0 11 11\n");
        DiskIoCollector.Result third = collector.collect(List.of());
        var sdb = third.rates.get("sdb");
        assertNotNull(sdb, "重新出现的设备回到结果");
        assertEquals(SampleState.FIRST_SAMPLE, sdb.getState(), "重新出现的设备本轮重建基线");
    }

    @Test
    @DisplayName("设备层级与挂载点关联：仅按设备 basename 匹配")
    void associatesMountPointByDeviceBasename() {
        FakeProvider provider = new FakeProvider();
        provider.put(DISKSTATS, "   8       1 sda1 100 10 5000 50 200 20 6000 60 0 88 200\n");
        DiskIoCollector collector = collectorWith(provider);
        collector.collect(List.of());
        provider.put(DISKSTATS, "   8       1 sda1 110 12 6000 60 220 24 7000 70 0 98 260\n");

        MountInfo mount = MountInfo.builder()
                .device("/dev/sda1").mountPoint("/").totalKiB(1000).usedKiB(500).availableKiB(500).usagePercent(50)
                .build();
        DiskIoCollector.Result result = collector.collect(List.of(mount));
        assertEquals("/", result.rates.get("sda1").getMountPoint());
    }

    @Test
    @DisplayName("采集失败时返回原因，不抛异常")
    void returnsFailureReasonWhenCommandFails() {
        FakeProvider provider = new FakeProvider();
        // 不 put 任何输出
        DiskIoCollector collector = collectorWith(provider);
        DiskIoCollector.Result result = collector.collect(List.of());
        assertNotNull(result.failureReason, "失败时应给出原因");
        assertTrue(result.rates.isEmpty());
    }

    @Test
    @DisplayName("列数不足的行被跳过，不误读错位字段")
    void skipsMalformedDiskstatsLines() {
        FakeProvider provider = new FakeProvider();
        provider.put(DISKSTATS,
                "   8       0 sda garbage line with too few fields\n" +
                "   8      16 sdb 100 10 5000 50 200 20 6000 60 0 88 200\n");
        DiskIoCollector collector = collectorWith(provider);
        collector.collect(List.of());
        provider.put(DISKSTATS,
                "   8      16 sdb 110 12 6000 60 220 24 7000 70 0 98 260\n");
        DiskIoCollector.Result result = collector.collect(List.of());
        assertEquals(1, result.rates.size(), "残缺行不应进入结果");
        assertTrue(result.rates.containsKey("sdb"));
    }
}
