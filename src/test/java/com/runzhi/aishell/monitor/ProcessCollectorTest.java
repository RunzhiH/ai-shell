package com.runzhi.aishell.monitor;

import com.runzhi.aishell.monitor.domain.ProcessInfo;
import com.runzhi.aishell.monitor.sampling.ProcessCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进程列表采集器测试（方案 §9.1：含空格/特殊字符的命令行、内核线程与降级）。
 */
class ProcessCollectorTest {

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

    private ProcessCollector collectorWith(FakeProvider provider) {
        return new ProcessCollector(new CommandRunner(provider, new java.util.concurrent.atomic.AtomicBoolean(false)));
    }

    /**
     * 构造一行 ps 输出：14 个固定列 + 命令行（保留空格与特殊字符）。
     */
    private static String psLine(int pid, int ppid, String user, double cpu, double mem, long rss,
                                 String stat, int threads, String startedAt, String elapsed, String name, String cmd) {
        return String.format("%d %d %s %.1f %.1f %d %s %d %s %s %s %s",
                pid, ppid, user, cpu, mem, rss, stat, threads, startedAt, elapsed, name, cmd);
    }

    private static final String LSTART = "Mon Nov 6 10:00:00 2026";

    @Test
    @DisplayName("命令行含空格与特殊字符时整段保留，不依赖固定空白拆分")
    void preservesCommandLineWithSpacesAndSpecialChars() {
        FakeProvider provider = new FakeProvider();
        String cmd = "/opt/my app/bin/start --name \"hello world\" --flag=a=b -Dx=1 2>&1";
        provider.put(ProcessCollector.PS_COMMAND, psLine(1234, 1, "root", 12.5, 3.2, 204800, "Ssl", 28, LSTART, "1-02:03:04", "start", cmd));

        ProcessCollector.Result result = collectorWith(provider).collect();

        assertEquals(1, result.processes.size());
        ProcessInfo info = result.processes.get(0);
        assertEquals(1234, info.getPid());
        assertEquals(1, info.getPpid());
        assertEquals("root", info.getUser());
        assertEquals(12.5, info.getCpuPercent(), 0.01);
        assertEquals(204800, info.getRssKiB());
        assertEquals("Ssl", info.getState());
        assertEquals(28, info.getThreadCount());
        assertEquals(LSTART, info.getStartedAt());
        assertEquals(cmd, info.getCommandLine(), "命令行必须原样保留");
        assertEquals("start", info.getName(), "进程名取命令行尾部可执行文件");
        assertEquals("1234@" + LSTART, info.getIdentityKey(), "身份键 = PID + 启动标识");
    }

    @Test
    @DisplayName("内核线程（命令行显示为 [kworker/...]）正常解析")
    void parsesKernelThreads() {
        FakeProvider provider = new FakeProvider();
        provider.put(ProcessCollector.PS_COMMAND,
                psLine(7, 2, "root", 0.0, 0.0, 0, "S", 1, LSTART, "12:34", "kworker/0:1-events", "[kworker/0:1-events]"));

        ProcessCollector.Result result = collectorWith(provider).collect();

        assertEquals(1, result.processes.size());
        assertEquals("kworker/0:1-events", result.processes.get(0).getName(), "内核线程名去括号");
        assertEquals(0, result.processes.get(0).getRssKiB());
    }

    @Test
    @DisplayName("状态首字母计数：运行/睡眠/停止/僵尸")
    void stateCountsMatchPsStates() {
        FakeProvider provider = new FakeProvider();
        provider.put(ProcessCollector.PS_COMMAND, String.join("\n",
                psLine(1, 0, "root", 0, 0, 100, "S", 1, LSTART, "1d", "init", "/sbin/init"),
                psLine(2, 1, "root", 5, 0, 100, "R", 1, LSTART, "1d", "busy", "/usr/bin/busy"),
                psLine(3, 1, "root", 0, 0, 100, "D", 1, LSTART, "1d", "io-wait", "/usr/bin/io-wait"),
                psLine(4, 1, "root", 0, 0, 100, "T", 1, LSTART, "1d", "stopped", "/usr/bin/stopped"),
                psLine(5, 1, "root", 0, 0, 0, "Z", 1, LSTART, "1d", "zombie", "[zombie]")));

        ProcessCollector.Result result = collectorWith(provider).collect();

        int[] counts = ProcessCollector.stateCounts(result.processes);
        // R=1, S(含 D)=2, T=1, Z=1
        assertEquals(1, counts[0], "运行");
        assertEquals(2, counts[1], "睡眠（S/D）");
        assertEquals(1, counts[2], "停止");
        assertEquals(1, counts[3], "僵尸");
        assertEquals(5, result.processes.size());
    }

    @Test
    @DisplayName("两次采样计算 RSS 与 CPU 变化（详情区「资源变化」）")
    void computesResourceDeltasAcrossSamples() {
        FakeProvider provider = new FakeProvider();
        provider.put(ProcessCollector.PS_COMMAND, psLine(1234, 1, "root", 10.0, 3.0, 200000, "S", 4, LSTART, "1h", "app", "java -jar app.jar"));
        ProcessCollector collector = collectorWith(provider);
        ProcessCollector.Result first = collector.collect();
        ProcessInfo firstInfo = first.processes.get(0);
        assertEquals(0, firstInfo.getRssDeltaKiB(), "首次采样无增量");

        provider.put(ProcessCollector.PS_COMMAND, psLine(1234, 1, "root", 25.0, 3.0, 215000, "S", 4, LSTART, "1h", "app", "java -jar app.jar"));
        ProcessCollector.Result second = collector.collect();
        ProcessInfo secondInfo = second.processes.get(0);
        assertEquals(15000, secondInfo.getRssDeltaKiB(), "RSS 变化 15000 KiB");
        assertEquals(15.0, secondInfo.getCpuDelta(), 0.01, "CPU 变化 +15.0");
    }

    @Test
    @DisplayName("进程 PID 复用：不同启动时间的同 PID 被视为不同身份")
    void distinguishesReusedPidByStartTime() {
        FakeProvider provider = new FakeProvider();
        provider.put(ProcessCollector.PS_COMMAND, psLine(1234, 1, "root", 1, 0, 1000, "S", 1, "Mon Nov 6 08:00:00 2026", "1h", "old", "/usr/bin/old"));
        ProcessCollector collector = collectorWith(provider);
        collector.collect();

        provider.put(ProcessCollector.PS_COMMAND, psLine(1234, 1, "root", 1, 0, 2000, "S", 1, "Mon Nov 6 09:00:00 2026", "10m", "new", "/usr/bin/new"));
        ProcessCollector.Result result = collector.collect();
        ProcessInfo info = result.processes.get(0);
        assertEquals("1234@Mon Nov 6 09:00:00 2026", info.getIdentityKey());
        assertNotEquals(1000, info.getRssDeltaKiB(), "新身份不应套用旧进程的增量");
    }

    @Test
    @DisplayName("单行解析失败只跳过该行，不使整张表失败")
    void skipsUnparseableRows() {
        FakeProvider provider = new FakeProvider();
        provider.put(ProcessCollector.PS_COMMAND, String.join("\n",
                "this is not a ps line at all",
                "truncated",
                psLine(1234, 1, "root", 1, 0, 1000, "S", 1, LSTART, "1h", "app", "app --flag")));

        ProcessCollector.Result result = collectorWith(provider).collect();

        assertEquals(1, result.processes.size(), "残缺行被跳过，有效行保留");
        assertEquals(1234, result.processes.get(0).getPid());
    }

    @Test
    @DisplayName("ps 命令失败时返回降级原因")
    void returnsDegradedReasonWhenPsFails() {
        FakeProvider provider = new FakeProvider();
        // 不 put PS_COMMAND 输出
        ProcessCollector.Result result = collectorWith(provider).collect();

        assertNotNull(result.failureReason, "采集失败应给出原因");
        assertTrue(result.processes.isEmpty());
    }

    @Test
    @DisplayName("完全无法解析（格式不兼容）时标记降级")
    void marksDegradedWhenAllRowsFail() {
        FakeProvider provider = new FakeProvider();
        provider.put(ProcessCollector.PS_COMMAND, "garbage output\nmore garbage");

        ProcessCollector.Result result = collectorWith(provider).collect();

        assertTrue(result.degraded, "所有行解析失败应降级");
        assertNotNull(result.failureReason);
    }
}
