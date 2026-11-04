package com.runzhi.aishell.monitor;

import com.runzhi.aishell.monitor.domain.SystemMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SystemMonitor 命令输出解析的回归测试。
 *
 * <p>注入一个按命令返回预设输出的假 DataSourceProvider，使解析逻辑可以在
 * 无真实 SSH 连接的情况下被验证。主要保护 /proc/stat 的 steal 字段解析
 * （旧内核缺失时按 0 处理）与内存/磁盘百分比计算。</p>
 */
class SystemMonitorParsingTest {

    /** 按命令返回预设输出的假数据源；同一命令可压入两个输出模拟两次采样（第一次基线、第二次增量） */
    private static class FakeDataSourceProvider implements DataSourceProvider {
        private final Map<String, String> outputs = new HashMap<>();
        private final Map<String, java.util.ArrayDeque<String>> sequences = new HashMap<>();
        private boolean connected = true;
        private int commandCount;

        void put(String command, String output) {
            outputs.put(command, output);
        }

        /** 同一命令依次返回 first、其后固定返回 second：模拟 /proc/stat 的两次采样。 */
        void putSequence(String command, String first, String second) {
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            queue.add(first);
            queue.add(second);
            sequences.put(command, queue);
        }

        void setConnected(boolean connected) {
            this.connected = connected;
        }

        int commandCount() {
            return commandCount;
        }

        @Override
        public String executeCommand(String command) {
            commandCount++;
            java.util.ArrayDeque<String> queue = sequences.get(command);
            if (queue != null) {
                return queue.size() > 1 ? queue.poll() : queue.peek();
            }
            return outputs.getOrDefault(command, "");
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private SystemMetrics collectMetrics(FakeDataSourceProvider provider) {
        SystemMonitor monitor = new SystemMonitor(provider);
        return monitor.getSystemMetrics();
    }

    @Test
    @DisplayName("/proc/stat 含 steal 字段时按两次采样的增量解析为百分比")
    void parsesCpuStatWithStealField() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        // CPU 百分比来自两次采样的增量（此前直接用累计计数算的是开机平均值）（2026-10-23 审计 P1-16）
        // 基线：cpu user nice system idle iowait irq softirq steal
        provider.putSequence("cat /proc/stat | head -n 1",
                "cpu  100 100 100 700 50 10 20 20 0 0",
                // 采样二：user+10 nice+5 system+10 idle+50 iowait+5 irq+2 softirq+5 steal+5
                "cpu  110 105 110 750 55 12 25 25 0 0");

        // 同一 monitor 连续采样：首次建基线（CPU 为 0），第二次按增量计算
        SystemMonitor monitor = new SystemMonitor(provider);
        SystemMetrics baseline = monitor.getSystemMetrics();
        assertEquals(0.0, baseline.getCpuSteal(), 0.001, "首次采样无增量来源应为 0");

        SystemMetrics metrics = monitor.getSystemMetrics();
        // 增量 total = 10+5+10+50+5+2+5+5 = 92
        assertEquals(5.0 * 100 / 92, metrics.getCpuSteal(), 0.01, "steal 百分比");
        assertEquals(10.0 * 100 / 92, metrics.getCpuUser(), 0.01, "user 百分比");
        assertEquals(50.0 * 100 / 92, metrics.getCpuIdle(), 0.01, "idle 百分比");
    }

    @Test
    @DisplayName("/proc/stat 缺少 steal 字段（旧内核）时按 0 处理且不崩溃")
    void parsesCpuStatWithoutStealField() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        // 只到 softirq，共 8 段（含 "cpu" 前缀），无 steal；两次采样看增量
        provider.putSequence("cat /proc/stat | head -n 1",
                "cpu  100 100 100 700 50 10 20",
                "cpu  110 105 110 750 55 12 25");

        SystemMonitor monitor = new SystemMonitor(provider);
        monitor.getSystemMetrics();   // 建基线
        SystemMetrics metrics = monitor.getSystemMetrics();

        // 增量 total = 10+5+10+50+5+2+5 = 87，steal 缺失按 0
        assertEquals(0.0, metrics.getCpuSteal(), 0.001, "无 steal 字段时应为 0");
        assertEquals(10.0 * 100 / 87, metrics.getCpuUser(), 0.01, "user 百分比");
        assertEquals(50.0 * 100 / 87, metrics.getCpuIdle(), 0.01, "idle 百分比");
    }

    @Test
    @DisplayName("/proc/stat 各字段全为 0 时不因除零崩溃")
    void handlesAllZeroCpuStatWithoutDivideByZero() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        provider.put("cat /proc/stat | head -n 1", "cpu  0 0 0 0 0 0 0 0");

        SystemMetrics metrics = collectMetrics(provider);

        assertNotNull(metrics, "即使所有计数为 0 也应返回对象");
        // total = 0+...+0+1 = 1，百分比为 0，不抛 ArithmeticException
        assertEquals(0.0, metrics.getCpuUser(), 0.001);
        assertEquals(0.0, metrics.getCpuSteal(), 0.001);
        assertFalse(Double.isNaN(metrics.getCpuIdle()), "idle 不应为 NaN");
        assertFalse(Double.isInfinite(metrics.getCpuIdle()), "idle 不应为 Infinity");
    }

    @Test
    @DisplayName("/proc/stat 输出格式错误时记日志而不抛异常")
    void handlesMalformedCpuStatGracefully() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        provider.put("cat /proc/stat | head -n 1", "cpu not-a-number garbage");

        SystemMetrics metrics = collectMetrics(provider);

        assertNotNull(metrics, "格式错误应被捕获，返回未填充 CPU 字段的对象");
        assertEquals(0.0, metrics.getCpuUser(), 0.001, "解析失败时 CPU 字段保持默认");
    }

    @Test
    @DisplayName("free -k 输出正确解析为内存总量与已用量")
    void parsesFreeOutputToMemoryFields() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        // free -k 的 Mem 行需要至少 7 段：Mem total used free shared buff/cache available
        provider.put("free -k",
                "              total        used        free      shared  buff/cache   available\n" +
                "Mem:        2000000     1000000     800000       0      200000     1500000");

        SystemMetrics metrics = collectMetrics(provider);

        assertEquals(2000000L, metrics.getTotalMemory(), "总内存");
        assertEquals(1000000L, metrics.getUsedMemory(), "已用内存");
        assertEquals(800000L, metrics.getFreeMemory(), "空闲内存");
    }

    @Test
    @DisplayName("free -k 的 Swap 行正确解析")
    void parsesFreeOutputSwapFields() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        provider.put("free -k",
                "              total        used        free      shared  buff/cache   available\n" +
                "Mem:        2000000     1000000     800000       0      200000     1500000\n" +
                "Swap:       1000000          0    1000000");

        SystemMetrics metrics = collectMetrics(provider);

        assertEquals(1000000L, metrics.getTotalSwap(), "总交换空间");
        assertEquals(0L, metrics.getUsedSwap(), "已用交换空间");
    }

    @Test
    @DisplayName("df -h 输出正确解析为磁盘使用百分比")
    void parsesDfOutputToDiskPercentage() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        provider.put("df -h",
                "Filesystem      Size  Used Avail Use% Mounted on\n" +
                "/dev/sda1        50G   25G   25G  50% /");

        SystemMetrics metrics = collectMetrics(provider);

        assertEquals(50.0, metrics.getDiskUsage(), 0.5, "磁盘使用率");
    }

    @Test
    @DisplayName("数据源未连接时指标字段保持默认且不抛异常")
    void returnsDefaultMetricsWhenDisconnected() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        provider.setConnected(false);

        SystemMetrics metrics = collectMetrics(provider);

        assertNotNull(metrics, "未连接时应返回默认对象而非抛异常");
    }

    /** 真实的 /proc/net/dev 片段：两行表头 + lo + 两个物理接口（8 列 rx 紧跟 8 列 tx） */
    private static final String NET_DEV_OUTPUT =
            "Inter-|   Receive                                                |  Transmit\n" +
            " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
            "    lo:    1000      10    0    0    0     0          0         0     1000      10    0    0    0     0       0          0\n" +
            "  eth0:    5000      20    0    0    0     0          0         0     7000      30    0    0    0     0       0          0\n" +
            " ens33:    2000      10    0    0    0     0          0         0     3000      15    0    0    0     0       0          0\n";

    @Test
    @DisplayName("/proc/net/dev 的表头行不被当作接口行解析（回归：For input string: \"|bytes\"）")
    void skipsProcNetDevHeaderRows() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        provider.put("cat /proc/net/dev", NET_DEV_OUTPUT);

        SystemMetrics metrics = collectMetrics(provider);

        // 只累计非 lo 接口：eth0 (5000/7000) + ens33 (2000/3000)，表头的 "|bytes" 不应进入解析
        assertEquals(7000L, metrics.getTotalRxBytes(), "接收字节数应为非 lo 接口之和");
        assertEquals(10000L, metrics.getTotalTxBytes(), "发送字节数应为非 lo 接口之和");
    }

    @Test
    @DisplayName("/proc/net/dev 列数不足的接口行被跳过，不会误读错位的列")
    void skipsTruncatedProcNetDevLine() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        // eth1 只有 rx 的 8 列，缺少 tx 的 8 列：旧实现会从 parts[9] 取到 rx 末列当发送量
        provider.put("cat /proc/net/dev",
                "Inter-|   Receive                                                |  Transmit\n" +
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                "  eth1:    1234       1    0    0    0     0          0         0\n");

        SystemMetrics metrics = collectMetrics(provider);

        assertEquals(0L, metrics.getTotalRxBytes(), "残缺行不应贡献接收字节数");
        assertEquals(0L, metrics.getTotalTxBytes(), "残缺行不应贡献发送字节数");
    }

    @Test
    @DisplayName("/proc/net/dev 连续两次采样按增量计算上传下载速度")
    void computesNetRateFromConsecutiveSamples() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        // 采样一作为基线，采样二 rx/tx 各增加 1024 字节
        provider.putSequence("cat /proc/net/dev",
                "Inter-|   Receive                                                |  Transmit\n" +
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                "  eth0:    1000      10    0    0    0     0          0         0     2000      20    0    0    0     0       0          0\n",
                "Inter-|   Receive                                                |  Transmit\n" +
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                "  eth0:    2024      12    0    0    0     0          0         0     3024      22    0    0    0     0       0          0\n");

        SystemMonitor monitor = new SystemMonitor(provider);
        SystemMetrics baseline = monitor.getSystemMetrics();
        assertNull(baseline.getNetUp(), "首次采样没有速率基线，不应给出速度");

        SystemMetrics metrics = monitor.getSystemMetrics();

        assertEquals(2024L, metrics.getTotalRxBytes(), "接收累计字节数");
        assertEquals(3024L, metrics.getTotalTxBytes(), "发送累计字节数");
        assertNotNull(metrics.getNetUp(), "第二次采样应算出上传速度");
        assertNotNull(metrics.getNetDown(), "第二次采样应算出下载速度");
        assertNotEquals("0 B", metrics.getNetUp(), "有增量时上传速度不应为 0");
        assertNotEquals("0 B", metrics.getNetDown(), "有增量时下载速度不应为 0");
    }

    @Test
    @DisplayName("所有命令返回空字符串时指标保持默认值")
    void handlesEmptyOutputsGracefully() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        // 不 put 任何命令，executeCommand 返回空串

        SystemMetrics metrics = collectMetrics(provider);

        assertNotNull(metrics);
        assertEquals(0.0, metrics.getCpuUser(), 0.001);
        assertEquals(0L, metrics.getUsedMemory(), "无输出时内存字段保持默认 0");
        assertEquals(0.0, metrics.getDiskUsage(), 0.001);
    }

    @Test
    @DisplayName("stopMonitoring 后不再发起新命令，关闭期间不产生 SSH 未连接错误")
    void stopsIssuingCommandsAfterStopping() {
        FakeDataSourceProvider provider = new FakeDataSourceProvider();
        SystemMonitor monitor = new SystemMonitor(provider);
        assertNotNull(monitor.getSystemMetrics(), "正常采样仍应工作");
        int beforeStop = provider.commandCount();

        monitor.stopMonitoring();

        assertNull(monitor.getSystemMetrics(), "停止后不再采样");
        assertEquals(beforeStop, provider.commandCount(), "停止后不得再向已断开的 SSH 会话发命令");
    }

}
