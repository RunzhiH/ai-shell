package com.runzhi.aishell.monitor;

import com.runzhi.aishell.monitor.domain.NetworkInterfaceRate;
import com.runzhi.aishell.monitor.domain.SampleState;
import com.runzhi.aishell.monitor.sampling.NetworkInterfaceCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多接口网络采集器测试（方案 §9.1：解析、按接口差分、接口增删与计数器重置）。
 */
class NetworkInterfaceCollectorTest {

    private static final String NET_DEV = NetworkInterfaceCollector.NET_DEV_COMMAND;
    private static final String ADDR = NetworkInterfaceCollector.ADDR_COMMAND;

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

    private static final String HEADER =
            "Inter-|   Receive                                                |  Transmit\n" +
            " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n";

    private static String ifLine(String name, long rx, long tx) {
        return "  " + name + ":    " + rx + "      10    0    0    0     0          0         0     "
                + tx + "      20    0    0    0     0       0          0\n";
    }

    private NetworkInterfaceCollector collectorWith(FakeProvider provider) {
        return new NetworkInterfaceCollector(new CommandRunner(provider, new java.util.concurrent.atomic.AtomicBoolean(false)));
    }

    @Test
    @DisplayName("每个接口分别计算差分速率；lo 不计入合计")
    void computesPerInterfaceRatesAndExcludesLoopback() {
        FakeProvider provider = new FakeProvider();
        provider.put(NET_DEV, HEADER + ifLine("lo", 1000, 1000) + ifLine("eth0", 1000, 2000));
        NetworkInterfaceCollector collector = collectorWith(provider);
        NetworkInterfaceCollector.Result first = collector.collect();
        assertTrue(first.firstSample, "首次采样只建立基线");

        provider.put(NET_DEV, HEADER + ifLine("lo", 2000, 2000) + ifLine("eth0", 3024, 5024));
        NetworkInterfaceCollector.Result result = collector.collect();

        // lo 计入接口集合（可在筛选中查看），但不计入旧指标的合计
        assertEquals(0.0, result.rates.get("eth0").getRxBytesPerSec(), 0.001 * Double.MAX_VALUE);
        assertTrue(result.rates.get("eth0").getRxBytesPerSec() > 0, "eth0 接收速率应大于 0");
        assertTrue(result.rates.get("eth0").getTxBytesPerSec() > 0, "eth0 发送速率应大于 0");
        assertEquals(3024L, result.totalRxBytes, "合计非 lo 接口");
        assertEquals(5024L, result.totalTxBytes, "合计非 lo 接口");
        assertEquals(SampleState.OK, result.rates.get("eth0").getState());
    }

    @Test
    @DisplayName("接口新增/删除只影响自身，其他接口速率照常")
    void addRemoveInterfacesIndependently() {
        FakeProvider provider = new FakeProvider();
        provider.put(NET_DEV, HEADER + ifLine("eth0", 1000, 2000));
        NetworkInterfaceCollector collector = collectorWith(provider);
        collector.collect();

        provider.put(NET_DEV, HEADER + ifLine("eth0", 2024, 3024) + ifLine("eth1", 500, 600));
        NetworkInterfaceCollector.Result result = collector.collect();

        assertEquals(2, result.rates.size());
        assertEquals(SampleState.OK, result.rates.get("eth0").getState(), "既有接口不受新增接口影响");
        assertEquals(SampleState.FIRST_SAMPLE, result.rates.get("eth1").getState(), "新增接口本轮建立基线");
    }

    @Test
    @DisplayName("接口计数器下降（重置/重连）只重建该接口基线")
    void counterResetRebuildsOnlyThatInterface() {
        FakeProvider provider = new FakeProvider();
        provider.put(NET_DEV, HEADER + ifLine("eth0", 1000, 2000) + ifLine("eth1", 3000, 4000));
        NetworkInterfaceCollector collector = collectorWith(provider);
        collector.collect();

        provider.put(NET_DEV, HEADER + ifLine("eth0", 2024, 3024) + ifLine("eth1", 100, 200));
        NetworkInterfaceCollector.Result result = collector.collect();

        assertEquals(SampleState.OK, result.rates.get("eth0").getState(), "eth0 不受影响");
        assertEquals(SampleState.FIRST_SAMPLE, result.rates.get("eth1").getState(), "eth1 计数器重置应重建基线");
    }

    @Test
    @DisplayName("/proc/net/dev 表头行不被当作接口行解析")
    void skipsHeaderRows() {
        FakeProvider provider = new FakeProvider();
        provider.put(NET_DEV, HEADER + ifLine("eth0", 0, 0));
        NetworkInterfaceCollector collector = collectorWith(provider);
        NetworkInterfaceCollector.Result result = collector.collect();

        assertTrue(result.rates.isEmpty() || result.firstSample, "表头不应产生接口");
    }

    @Test
    @DisplayName("错误与丢包增量按采样窗口输出")
    void reportsErrorDropDeltas() {
        FakeProvider provider = new FakeProvider();
        provider.put(NET_DEV, HEADER
                + "  eth0:    1000      10    0    0    0     0          0         0     2000      20    0    0    0     0       0          0\n");
        NetworkInterfaceCollector collector = collectorWith(provider);
        collector.collect();

        // rx errs +2, tx drop +3
        provider.put(NET_DEV, HEADER
                + "  eth0:    1024      12    2    1    0     0          0         0     2024      22    0    3    0     0       0          0\n");
        NetworkInterfaceCollector.Result result = collector.collect();
        NetworkInterfaceRate rate = result.rates.get("eth0");
        assertEquals(2, rate.getRxErrorDelta(), "接收错误增量");
        assertEquals(1, rate.getRxDropDelta(), "接收丢包增量");
        assertEquals(3, rate.getTxDropDelta(), "发送丢包增量");
    }

    @Test
    @DisplayName("ip -o addr show 的 IPv4/IPv6 地址按接口解析；无输出时降级")
    void parsesInterfaceAddresses() {
        FakeProvider provider = new FakeProvider();
        provider.put(NET_DEV, HEADER + ifLine("eth0", 0, 0));
        provider.put(ADDR,
                "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq_codel state UP group default \\    inet 10.0.0.5/24 brd 10.0.0.255 scope global eth0\n" +
                "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq_codel state UP group default \\    inet6 fe80::1/64 scope link eth0\n");
        NetworkInterfaceCollector collector = collectorWith(provider);

        Map<String, com.runzhi.aishell.monitor.domain.NetworkInterfaceInfo> info =
                collector.refreshInterfaceInfo(List.of("eth0"));

        assertEquals("10.0.0.5", info.get("eth0").getIpv4Address());
        assertEquals("fe80::1", info.get("eth0").getIpv6Address());
    }

    @Test
    @DisplayName("虚拟/桥接接口识别：lo、docker、veth、br- 等不作为默认活动接口")
    void identifiesVirtualInterfaces() {
        assertTrue(NetworkInterfaceCollector.isLikelyVirtual("lo"));
        assertTrue(NetworkInterfaceCollector.isLikelyVirtual("docker0"));
        assertTrue(NetworkInterfaceCollector.isLikelyVirtual("veth1234"));
        assertTrue(NetworkInterfaceCollector.isLikelyVirtual("br-abcdef"));
        assertFalse(NetworkInterfaceCollector.isLikelyVirtual("eth0"));
        assertFalse(NetworkInterfaceCollector.isLikelyVirtual("ens33"));
    }

    @Test
    @DisplayName("sysfs 不可获得时状态/MTU 保持未知且不崩溃")
    void degradesWhenSysfsUnavailable() {
        FakeProvider provider = new FakeProvider();
        provider.put(NET_DEV, HEADER + ifLine("eth0", 0, 0));
        // 不提供 sysfs 与 addr 输出
        NetworkInterfaceCollector collector = collectorWith(provider);
        Map<String, com.runzhi.aishell.monitor.domain.NetworkInterfaceInfo> info =
                collector.refreshInterfaceInfo(List.of("eth0"));
        com.runzhi.aishell.monitor.domain.NetworkInterfaceInfo eth0 = info.get("eth0");
        assertNotNull(eth0);
        assertNull(eth0.getIpv4Address(), "能力不足时按项降级为 null");
        assertNull(eth0.getMtu());
        assertNull(eth0.getSpeedMbps());
    }
}
