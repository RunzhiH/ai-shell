/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor;

import com.runzhi.aishell.common.util.ThreadPoolManager;
import com.runzhi.aishell.monitor.domain.DiskIoRate;
import com.runzhi.aishell.monitor.domain.MetricStatus;
import com.runzhi.aishell.monitor.domain.MonitorSnapshot;
import com.runzhi.aishell.monitor.domain.MountInfo;
import com.runzhi.aishell.monitor.domain.NetworkInterfaceInfo;
import com.runzhi.aishell.monitor.domain.NetworkInterfaceRate;
import com.runzhi.aishell.monitor.domain.ProcessInfo;
import com.runzhi.aishell.monitor.domain.SampleState;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import com.runzhi.aishell.monitor.domain.SystemSample;
import com.runzhi.aishell.monitor.sampling.DiskIoCollector;
import com.runzhi.aishell.monitor.sampling.NetworkInterfaceCollector;
import com.runzhi.aishell.monitor.sampling.ProcessCollector;
import com.runzhi.aishell.monitor.sampling.SystemMetricsCollector;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 连接级监控会话：一个 SSH 连接对应一个会话，会话标识（sessionId）随每次启动重建。
 *
 * <p>职责（方案 §7）：
 * <ul>
 *   <li>采样调度：快速通道（CPU/内存/磁盘 I/O/网络）、进程通道（5 秒，进入进程页后启用）、
 *       低频通道（容量/接口地址/扇区大小，30 秒），同一通道不重叠执行，采样过慢时跳过本轮；</li>
 *   <li>单项失败只影响该项状态：每个数据区独立携带 {@link SampleState}；</li>
 *   <li>暂停/恢复、重连后重建差分基线，避免暂停区间形成速率突刺；</li>
 *   <li>快照携带会话标识，界面切换主机后旧会话返回的数据必须被丢弃。</li>
 * </ul>
 *
 * <p>监听器在采样线程被调用，界面侧需自行切回 JavaFX 线程。
 */
@Slf4j
public class MonitorSession {

    /** 会话标识：区分不同连接/实例的采样代次。 */
    @Getter
    private final String sessionId = UUID.randomUUID().toString();

    private final DataSourceProvider dataSourceProvider;
    private final CommandRunner runner;
    private final SystemMetricsCollector systemCollector;
    private final DiskIoCollector diskIoCollector;
    private final NetworkInterfaceCollector networkCollector;
    private final ProcessCollector processCollector;

    private final List<Consumer<MonitorSnapshot>> listeners = new CopyOnWriteArrayList<>();

    /** 快速通道间隔（毫秒），只控制调度；速率用实际采样时间差计算。 */
    private volatile long intervalMs;
    private volatile boolean processSamplingEnabled;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> fastTask;
    private ScheduledFuture<?> processTask;
    private ScheduledFuture<?> infoTask;
    private final AtomicBoolean fastBusy = new AtomicBoolean();
    private final AtomicBoolean processBusy = new AtomicBoolean();
    private final AtomicBoolean infoBusy = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile boolean started;

    // 各数据区最近一次结果（跨通道共享，各自携带状态与时间）
    private volatile SystemSample lastSystem;
    private volatile MetricStatus<List<MountInfo>> lastMounts;
    private volatile MetricStatus<Map<String, DiskIoRate>> lastDiskIo;
    private volatile MetricStatus<Map<String, NetworkInterfaceRate>> lastNetworkRates;
    private volatile MetricStatus<Map<String, NetworkInterfaceInfo>> lastNetworkInfo;
    private volatile MetricStatus<List<ProcessInfo>> lastProcesses;

    public MonitorSession(DataSourceProvider dataSourceProvider, long intervalMs) {
        this(dataSourceProvider, intervalMs, System::nanoTime);
    }

    /** 测试可注入受控时钟；生产使用 {@link System#nanoTime()}（不受墙上时钟调整影响）。 */
    MonitorSession(DataSourceProvider dataSourceProvider, long intervalMs, java.util.function.LongSupplier nanoClock) {
        this.dataSourceProvider = dataSourceProvider;
        this.runner = new CommandRunner(dataSourceProvider, stopped);
        this.systemCollector = new SystemMetricsCollector(runner);
        this.diskIoCollector = new DiskIoCollector(runner, nanoClock);
        this.networkCollector = new NetworkInterfaceCollector(runner, nanoClock);
        this.processCollector = new ProcessCollector(runner);
        this.intervalMs = Math.max(500, intervalMs);
        this.scheduler = ThreadPoolManager.getInstance().getScheduledThreadPool();
    }

    public void addListener(Consumer<MonitorSnapshot> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<MonitorSnapshot> listener) {
        listeners.remove(listener);
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = Math.max(500, intervalMs);
        if (started && fastTask != null) {
            rescheduleFastTask();
        }
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    /** 进入进程页后启用进程列表采样；离开时关闭以节省开销。 */
    public void setProcessSamplingEnabled(boolean enabled) {
        boolean wasEnabled = this.processSamplingEnabled;
        this.processSamplingEnabled = enabled;
        if (enabled && !wasEnabled && started) {
            // 立即触发一次完整采样，避免进入页面后空等 5 秒
            ThreadPoolManager.getInstance().getIoThreadPool().execute(this::runProcessCycleGuarded);
        }
    }

    public boolean isProcessSamplingEnabled() {
        return processSamplingEnabled;
    }

    /** 启动采样调度。 */
    public synchronized void start() {
        if (started || stopped.get()) return;
        started = true;
        stopped.set(false);
        scheduleFastTask();
        try {
            processTask = scheduler.scheduleWithFixedDelay(
                    this::runProcessCycleGuarded, intervalMs, PROCESS_INTERVAL_MS, TimeUnit.MILLISECONDS);
            infoTask = scheduler.scheduleWithFixedDelay(
                    this::runInfoCycleGuarded, 0, INFO_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Could not schedule monitor tasks: {}", e.getMessage());
        }
        log.debug("Monitor session {} started ({}ms)", sessionId, intervalMs);
    }

    /** 停止采样并释放调度任务；会话停止后命令执行器不再发起新命令。 */
    public synchronized void stop() {
        if (stopped.getAndSet(true)) return;
        started = false;
        cancel(fastTask);
        cancel(processTask);
        cancel(infoTask);
        fastTask = null;
        processTask = null;
        infoTask = null;
        resetBaselines();
        log.debug("Monitor session {} stopped", sessionId);
    }

    /**
     * 暂停采样（同时停止实时更新）。恢复时重建差分基线，避免暂停区间形成速率突刺。
     */
    public synchronized void pause() {
        if (!started) return;
        cancel(fastTask);
        cancel(processTask);
        cancel(infoTask);
        fastTask = null;
        processTask = null;
        infoTask = null;
        log.debug("Monitor session {} paused", sessionId);
    }

    public synchronized void resume() {
        if (stopped.get()) return;
        if (!started) {
            start();
        } else if (fastTask == null) {
            // 恢复采样前重建基线：暂停期间的计数器增量不应形成速率突刺
            resetBaselines();
            scheduleFastTask();
            try {
                processTask = scheduler.scheduleWithFixedDelay(
                        this::runProcessCycleGuarded, intervalMs, PROCESS_INTERVAL_MS, TimeUnit.MILLISECONDS);
                infoTask = scheduler.scheduleWithFixedDelay(
                        this::runInfoCycleGuarded, 0, INFO_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Could not resume monitor tasks: {}", e.getMessage());
            }
        }
    }

    public boolean isPausedOrStopped() {
        return stopped.get() || started && fastTask == null;
    }

    /** 手动刷新：复用调度入口，不创建重叠采样任务。 */
    public void triggerNow() {
        ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            runFastCycleGuarded();
            runInfoCycleGuarded();
            if (processSamplingEnabled) {
                runProcessCycleGuarded();
            }
        });
    }

    /** 采样间隔与通道默认配置。 */
    private static final long PROCESS_INTERVAL_MS = 5000;
    private static final long INFO_INTERVAL_MS = 30000;

    private void scheduleFastTask() {
        cancel(fastTask);
        try {
            fastTask = scheduler.scheduleWithFixedDelay(
                    this::runFastCycleGuarded, 0, intervalMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Could not schedule fast sampling: {}", e.getMessage());
        }
    }

    private void rescheduleFastTask() {
        if (started && fastTask != null) {
            scheduleFastTask();
        }
    }

    private void resetBaselines() {
        systemCollector.resetBaseline();
        diskIoCollector.resetBaseline();
        networkCollector.resetBaseline();
        processCollector.resetBaseline();
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) future.cancel(true);
    }

    /** 快速通道：系统指标 + 磁盘 I/O + 网络速率。 */
    private void runFastCycleGuarded() {
        if (stopped.get()) return;
        if (!fastBusy.compareAndSet(false, true)) {
            // 上一轮尚未完成：跳过本轮，避免积压
            return;
        }
        try {
            long start = System.currentTimeMillis();
            SystemSample system = systemCollector.collect();
            lastSystem = system;
           MetricStatus<List<MountInfo>> mounts = lastMounts;
            List<MountInfo> mountList = mounts != null && mounts.isUsable() ? mounts.getValue() : List.of();

            DiskIoCollector.Result disk = diskIoCollector.collect(mountList);
            lastDiskIo = toDiskIoStatus(disk, lastDiskIo);

            NetworkInterfaceCollector.Result network = networkCollector.collect();
            lastNetworkRates = toNetworkStatus(network, lastNetworkRates);

            publish(start);
        } catch (Throwable t) {
            // 单轮失败不应终止后续调度
            log.warn("Fast sampling cycle failed: {}", t.getMessage());
        } finally {
            fastBusy.set(false);
        }
    }

    /** 进程通道：进入进程页后启用。 */
    private void runProcessCycleGuarded() {
        if (stopped.get() || !processSamplingEnabled) return;
        if (!processBusy.compareAndSet(false, true)) return;
        try {
            ProcessCollector.Result result = processCollector.collect();
            lastProcesses = toProcessStatus(result, lastProcesses);
            publish(System.currentTimeMillis());
        } catch (Throwable t) {
            log.warn("Process sampling cycle failed: {}", t.getMessage());
        } finally {
            processBusy.set(false);
        }
    }

    /** 低频通道：容量、接口地址/MTU/状态、设备扇区大小。 */
    private void runInfoCycleGuarded() {
        if (stopped.get()) return;
        if (!infoBusy.compareAndSet(false, true)) return;
        try {
            com.runzhi.aishell.monitor.domain.MountsSample mounts = systemCollector.collectMounts();
            if (mounts.isAvailable()) {
                lastMounts = MetricStatus.ok(mounts.getMounts());
            } else if (lastMounts != null && lastMounts.isUsable()) {
                lastMounts = lastMounts.toStaleOrUpdate(SampleState.FAILED, mounts::getFailureReason);
            } else {
                lastMounts = MetricStatus.failed(mounts.getFailureReason());
            }

            MetricStatus<Map<String, NetworkInterfaceRate>> rates = lastNetworkRates;
            java.util.Collection<String> interfaces = rates != null && rates.getValue() != null
                    ? rates.getValue().keySet() : java.util.List.of();
            Map<String, NetworkInterfaceInfo> info = networkCollector.refreshInterfaceInfo(interfaces);
            lastNetworkInfo = MetricStatus.ok(info);

            java.util.Collection<String> devices = lastDiskIo != null && lastDiskIo.getValue() != null
                    ? lastDiskIo.getValue().keySet() : java.util.List.of();
            diskIoCollector.refreshSectorSizes(devices);

            // 系统指标尚未就绪时不发布空快照，避免界面把缺数据误显示为 0
            if (lastSystem != null) {
                publish(System.currentTimeMillis());
            }
        } catch (Throwable t) {
            log.warn("Info sampling cycle failed: {}", t.getMessage());
        } finally {
            infoBusy.set(false);
        }
    }

    private void publish(long startMs) {
        MonitorSnapshot snapshot = assemble(startMs);
        for (Consumer<MonitorSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Throwable t) {
                log.warn("Monitor listener failed: {}", t.getMessage());
            }
        }
    }

    /** 把各数据区最近一次结果合并为快照。 */
    private MonitorSnapshot assemble(long startMs) {
        SystemMetrics metrics = lastSystem != null ? lastSystem.getSystemMetrics()
                : SystemMetrics.builder().build();

        // 容量来自低频通道：合并进系统指标，并保留状态
        MetricStatus<List<MountInfo>> mounts = lastMounts;
        if (mounts != null && mounts.isUsable() && mounts.getValue() != null) {
            double maxUsage = SystemMetricsCollector.maxUsageOf(mounts.getValue());
            metrics.setDiskUsage(maxUsage);
            metrics.setDiskUsageString(String.format("%.1f%%", maxUsage));
        }

        // 磁盘 I/O 合计读写字节（旧指标字段）
        MetricStatus<Map<String, DiskIoRate>> diskIo = lastDiskIo;
        if (diskIo != null && diskIo.getValue() != null) {
            long readBytes = 0;
            long writeBytes = 0;
            for (DiskIoRate rate : diskIo.getValue().values()) {
                if (DiskIoCollector.isPhysicalDeviceLevel(rate.getDeviceLevel())) {
                    readBytes += rate.getTotalReadBytes();
                    writeBytes += rate.getTotalWriteBytes();
                }
            }
            metrics.setDiskReadBytes(readBytes);
            metrics.setDiskWriteBytes(writeBytes);
        }

        // 网络：合计非 lo 接口的累计字节与速率（旧指标字段）
        MetricStatus<Map<String, NetworkInterfaceRate>> network = lastNetworkRates;
        if (network != null && network.getValue() != null) {
            long totalRx = 0;
            long totalTx = 0;
            double rxRate = 0;
            double txRate = 0;
            for (NetworkInterfaceRate rate : network.getValue().values()) {
                if (!"lo".equals(rate.getName())) {
                    totalRx += rate.getTotalRxBytes();
                    totalTx += rate.getTotalTxBytes();
                    if (rate.getState() == SampleState.OK) {
                        rxRate += rate.getRxBytesPerSec();
                        txRate += rate.getTxBytesPerSec();
                    }
                }
            }
            metrics.setTotalRxBytes(totalRx);
            metrics.setTotalTxBytes(totalTx);
            if (!network.getValue().isEmpty() && hasUsableRate(network.getValue())) {
                metrics.setNetUp(MonitorFormats.legacyNetworkSpeed((long) txRate));
                metrics.setNetDown(MonitorFormats.legacyNetworkSpeed((long) rxRate));
            }
        }

        // 进程列表：状态计数合并进旧指标
        MetricStatus<List<ProcessInfo>> processes = lastProcesses;
        if (processes != null && processes.getValue() != null) {
            int[] counts = ProcessCollector.stateCounts(processes.getValue());
            metrics.setTotalProcesses(processes.getValue().size());
            metrics.setTotalTasks(processes.getValue().size());
            metrics.setRunningTasks(counts[0]);
            metrics.setSleepingTasks(counts[1]);
            metrics.setStoppedTasks(counts[2]);
            metrics.setZombieTasks(counts[3]);
        }

        return MonitorSnapshot.builder()
                .sessionId(sessionId)
                .epochMs(System.currentTimeMillis())
                .durationMs(System.currentTimeMillis() - startMs)
                .systemMetrics(metrics)
                .cpuFirstSample(lastSystem != null && lastSystem.isCpuFirstSample())
                .mounts(mounts != null ? mounts : MetricStatus.<List<MountInfo>>noData())
                .diskIo(diskIo != null ? diskIo : MetricStatus.<Map<String, DiskIoRate>>noData())
                .networkRates(network != null ? network : MetricStatus.<Map<String, NetworkInterfaceRate>>noData())
                .networkInfo(lastNetworkInfo != null ? lastNetworkInfo
                        : MetricStatus.<Map<String, NetworkInterfaceInfo>>noData())
                .processes(processes != null ? processes : MetricStatus.<List<ProcessInfo>>noData())
                .build();
    }

    private static boolean hasUsableRate(Map<String, NetworkInterfaceRate> rates) {
        return rates.values().stream()
                .anyMatch(rate -> rate.getState() == SampleState.OK);
    }

    private static MetricStatus<Map<String, DiskIoRate>> toDiskIoStatus(
            DiskIoCollector.Result result, MetricStatus<Map<String, DiskIoRate>> previous) {
        if (result.firstSample) {
            return MetricStatus.firstSample(result.rates);
        }
        if (result.failureReason == null) {
            return MetricStatus.ok(result.rates);
        }
        return previous != null
                ? previous.toStaleOrUpdate(SampleState.FAILED, () -> result.failureReason)
                : MetricStatus.failed(result.failureReason);
    }

    private static MetricStatus<Map<String, NetworkInterfaceRate>> toNetworkStatus(
            NetworkInterfaceCollector.Result result,
            MetricStatus<Map<String, NetworkInterfaceRate>> previous) {
        if (result.firstSample) {
            return MetricStatus.firstSample(result.rates);
        }
        if (result.failureReason == null) {
            return MetricStatus.ok(result.rates);
        }
        return previous != null
                ? previous.toStaleOrUpdate(SampleState.FAILED, () -> result.failureReason)
                : MetricStatus.failed(result.failureReason);
    }

    private static MetricStatus<List<ProcessInfo>> toProcessStatus(
            ProcessCollector.Result result, MetricStatus<List<ProcessInfo>> previous) {
        if (result.degraded) {
            return previous != null
                    ? previous.toStaleOrUpdate(SampleState.DEGRADED, () -> result.failureReason)
                    : MetricStatus.degraded(result.processes, result.failureReason);
        }
        if (result.failed()) {
            return previous != null
                    ? previous.toStaleOrUpdate(SampleState.FAILED, () -> result.failureReason)
                    : MetricStatus.failed(result.failureReason);
        }
        return MetricStatus.ok(result.processes);
    }

    /** 会话是否仍可使用（已启动、未停止且数据源仍连接）。 */
    public boolean isUsable() {
        return started && !stopped.get() && dataSourceProvider != null && dataSourceProvider.isConnected();
    }
}
