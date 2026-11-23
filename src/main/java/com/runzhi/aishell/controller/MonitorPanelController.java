/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.util.UITools;
import com.runzhi.aishell.monitor.MonitorFormats;
import com.runzhi.aishell.monitor.SystemMonitor;
import com.runzhi.aishell.monitor.domain.DiskIoRate;
import com.runzhi.aishell.monitor.domain.MetricStatus;
import com.runzhi.aishell.monitor.domain.MonitorSnapshot;
import com.runzhi.aishell.monitor.domain.MountInfo;
import com.runzhi.aishell.monitor.domain.NetworkInterfaceInfo;
import com.runzhi.aishell.monitor.domain.NetworkInterfaceRate;
import com.runzhi.aishell.monitor.domain.ProcessInfo;
import com.runzhi.aishell.monitor.domain.SampleState;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import com.runzhi.aishell.monitor.sampling.DiskIoCollector;
import com.runzhi.aishell.monitor.sampling.MetricHistory;
import com.runzhi.aishell.monitor.sampling.NetworkInterfaceCollector;
import com.runzhi.aishell.monitor.sampling.SystemMetricsCollector;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.impl.SSHServiceImpl;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主机监控面板：概览 / 磁盘 I/O / 进程 / 网络接口四个视图共用当前 SSH 连接与采样会话。
 *
 * <p>统一交互规则（方案 §3）：
 * <ul>
 *   <li>采样失败保留最后成功值并标注「已过期」，无历史数据显示 「—」；</li>
 *   <li>首次差分采样显示「采样中」，正常空闲显示 0；</li>
 *   <li>暂停同时停止采样与实时更新，恢复后重建差分基线；</li>
 *   <li>切换主机后旧会话返回的数据按会话标识丢弃；</li>
 *   <li>进程页进入后才启用进程列表采样；历史缓存与曲线点数均设上限。</li>
 * </ul>
 */
@lombok.EqualsAndHashCode(callSuper = false)
@Data
@Slf4j
public class MonitorPanelController extends BaseController implements Initializable {

    // 告警阈值配置（百分比）
    private static final double CPU_WARNING_THRESHOLD = 70.0;
    private static final double CPU_CRITICAL_THRESHOLD = 90.0;
    private static final double MEMORY_WARNING_THRESHOLD = 75.0;
    private static final double MEMORY_CRITICAL_THRESHOLD = 90.0;
    private static final double DISK_WARNING_THRESHOLD = 80.0;
    private static final double DISK_CRITICAL_THRESHOLD = 95.0;

    /** 有界历史缓存上限（约 2 秒采样 × 15 分钟）。 */
    private static final int MAX_HISTORY_POINTS = MetricHistory.DEFAULT_MAX_POINTS;
    /** 每个图表缓存的设备/接口历史条目上限。 */
    private static final int MAX_TRACKED_SERIES = 64;
    /** X 轴保留当前时间之后的填充，窗口 = 趋势范围 + 5 秒。 */
    private static final long CHART_FUTURE_PADDING = 5000;

    /** 趋势时间轴的主刻度步长随范围变化：1 分钟→10 秒，5 分钟→60 秒，15 分钟→120 秒。 */
    private static final Map<Long, Double> TICK_UNIT_BY_RANGE = Map.of(
            60_000L, 10_000.0,
            300_000L, 60_000.0,
            900_000L, 120_000.0);
    private static final long DEFAULT_TREND_RANGE_MS = 60_000L;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML private Label hostLabel;
    @FXML private Label connectionStateLabel;
    @FXML private Label lastUpdateLabel;
    @FXML private ComboBox<String> intervalComboBox;
    @FXML private Button pauseResumeButton;
    @FXML private Button refreshBtn;

    @FXML private TabPane monitorTabPane;
    @FXML private Tab overviewTab;
    @FXML private Tab diskIoTab;
    @FXML private Tab processTab;
    @FXML private Tab networkTab;

    // 概览指标卡
    @FXML private Label cpuPercentLabel;
    @FXML private Label memoryPercentLabel;
    @FXML private Label diskPercentLabel;
    @FXML private Label netRateLabel;
    @FXML private Label netScopeLabel;
    @FXML private Label loadLabel;
    @FXML private Label uptimeLabel;
    @FXML private Label memoryLabel;
    @FXML private Label swapLabel;
    @FXML private Label samplingStateLabel;

    @FXML private ProgressBar cpuProgress;
    @FXML private ProgressBar memoryProgress;
    @FXML private ProgressBar diskProgress;

    @FXML private ComboBox<String> trendRangeComboBox;
    private volatile long trendRangeMs = DEFAULT_TREND_RANGE_MS;

    // 概览图表（保留原有 id，供时间轴回归测试使用）
    @FXML private LineChart<Number, Number> cpuChart;
    @FXML private LineChart<Number, Number> memoryChart;
    @FXML private LineChart<Number, Number> diskChart;
    @FXML private NumberAxis cpuXAxis, cpuYAxis;
    @FXML private NumberAxis memoryXAxis, memoryYAxis;
    @FXML private NumberAxis diskXAxis, diskYAxis;

    // 概览补充表格
    @FXML private TableView<DiskIoRate> overviewDiskTable;
    @FXML private TableColumn<DiskIoRate, String> ovDiskDeviceColumn;
    @FXML private TableColumn<DiskIoRate, String> ovDiskReadColumn;
    @FXML private TableColumn<DiskIoRate, String> ovDiskWriteColumn;
    @FXML private TableColumn<DiskIoRate, String> ovDiskBusyColumn;
    @FXML private TableView<ProcessInfo> topProcessTable;
    @FXML private TableColumn<ProcessInfo, Number> topPidColumn;
    @FXML private TableColumn<ProcessInfo, String> topNameColumn;
    @FXML private TableColumn<ProcessInfo, Number> topCpuColumn;
    @FXML private TableColumn<ProcessInfo, Number> topMemColumn;
    @FXML private TableView<NetworkInterfaceRate> activeInterfaceTable;
    @FXML private TableColumn<NetworkInterfaceRate, String> aiNameColumn;
    @FXML private TableColumn<NetworkInterfaceRate, String> aiRxColumn;
    @FXML private TableColumn<NetworkInterfaceRate, String> aiTxColumn;

    // 磁盘 I/O 视图
    @FXML private Label diskScopeLabel;
    @FXML private Label diskDetailLabel;
    @FXML private TableView<DiskIoRate> diskIoTable;
    @FXML private TableColumn<DiskIoRate, String> diskDeviceColumn;
    @FXML private TableColumn<DiskIoRate, String> diskReadRateColumn;
    @FXML private TableColumn<DiskIoRate, String> diskWriteRateColumn;
    @FXML private TableColumn<DiskIoRate, Number> diskReadIopsColumn;
    @FXML private TableColumn<DiskIoRate, Number> diskWriteIopsColumn;
    @FXML private TableColumn<DiskIoRate, String> diskLatencyColumn;
    @FXML private TableColumn<DiskIoRate, String> diskBusyColumn;
    @FXML private TableColumn<DiskIoRate, String> diskLevelColumn;
    @FXML private TableColumn<DiskIoRate, String> diskMountColumn;
    @FXML private LineChart<Number, Number> diskThroughputChart;
    @FXML private NumberAxis diskThroughputXAxis, diskThroughputYAxis;
    @FXML private LineChart<Number, Number> diskIopsChart;
    @FXML private NumberAxis diskIopsXAxis, diskIopsYAxis;

    // 进程视图
    @FXML private TextField processSearchField;
    @FXML private ComboBox<String> processStateFilter;
    @FXML private CheckBox processAutoRefreshCheckBox;
    @FXML private CheckMenuItem showPpidMenuItem;
    @FXML private CheckMenuItem showThreadsMenuItem;
    @FXML private CheckMenuItem showStartMenuItem;
    @FXML private TableView<ProcessInfo> processTable;
    @FXML private TableColumn<ProcessInfo, Number> procPidColumn;
    @FXML private TableColumn<ProcessInfo, String> procNameColumn;
    @FXML private TableColumn<ProcessInfo, String> procUserColumn;
    @FXML private TableColumn<ProcessInfo, Number> procCpuColumn;
    @FXML private TableColumn<ProcessInfo, Number> procMemColumn;
    @FXML private TableColumn<ProcessInfo, Number> procRssColumn;
    @FXML private TableColumn<ProcessInfo, String> procStateColumn;
    @FXML private TableColumn<ProcessInfo, String> procElapsedColumn;
    @FXML private TableColumn<ProcessInfo, Number> procPpidColumn;
    @FXML private TableColumn<ProcessInfo, Number> procThreadsColumn;
    @FXML private TableColumn<ProcessInfo, String> procStartColumn;
    @FXML private Label processDetailLabel;

    // 网络接口视图
    @FXML private CheckBox showVirtualInterfacesCheckBox;
    @FXML private Label networkScopeLabel;
    @FXML private Label networkDetailLabel;
    @FXML private TableView<NetworkInterfaceRate> networkTable;
    @FXML private TableColumn<NetworkInterfaceRate, String> netNameColumn;
    @FXML private TableColumn<NetworkInterfaceRate, String> netStateColumn;
    @FXML private TableColumn<NetworkInterfaceRate, String> netRxRateColumn;
    @FXML private TableColumn<NetworkInterfaceRate, String> netTxRateColumn;
    @FXML private TableColumn<NetworkInterfaceRate, String> netRxTotalColumn;
    @FXML private TableColumn<NetworkInterfaceRate, String> netTxTotalColumn;
    @FXML private TableColumn<NetworkInterfaceRate, Number> netRxPpsColumn;
    @FXML private TableColumn<NetworkInterfaceRate, Number> netTxPpsColumn;
    @FXML private TableColumn<NetworkInterfaceRate, String> netErrDropColumn;
    @FXML private LineChart<Number, Number> networkChart;
    @FXML private NumberAxis networkXAxis, networkYAxis;

    // 概览趋势序列
    private XYChart.Series<Number, Number> cpuSeries;
    private XYChart.Series<Number, Number> memorySeries;
    private XYChart.Series<Number, Number> diskSeries;
    // 磁盘 I/O 趋势序列（选中设备）
    private XYChart.Series<Number, Number> diskReadSeries;
    private XYChart.Series<Number, Number> diskWriteSeries;
    private XYChart.Series<Number, Number> diskReadIopsSeries;
    private XYChart.Series<Number, Number> diskWriteIopsSeries;
    // 网络趋势序列（选中接口）
    private XYChart.Series<Number, Number> networkRxSeries;
    private XYChart.Series<Number, Number> networkTxSeries;

    // 有界历史缓存
    private final MetricHistory cpuHistory = new MetricHistory(MAX_HISTORY_POINTS);
    private final MetricHistory memoryHistory = new MetricHistory(MAX_HISTORY_POINTS);
    private final MetricHistory diskUsageHistory = new MetricHistory(MAX_HISTORY_POINTS);
    private final Map<String, MetricHistory> diskThroughputReadHistories = new ConcurrentHashMap<>();
    private final Map<String, MetricHistory> diskThroughputWriteHistories = new ConcurrentHashMap<>();
    private final Map<String, MetricHistory> diskIopsReadHistories = new ConcurrentHashMap<>();
    private final Map<String, MetricHistory> diskIopsWriteHistories = new ConcurrentHashMap<>();
    private final Map<String, MetricHistory> netRxHistories = new ConcurrentHashMap<>();
    private final Map<String, MetricHistory> netTxHistories = new ConcurrentHashMap<>();

    // 为每个Tab维护独立的系统监控器
    private final Map<String, SystemMonitor> tabSystemMonitors = new ConcurrentHashMap<>();
    private SystemMonitor currentSystemMonitor;
    private SSHServiceImpl sshService;
    private String currentTabId;
    /** 当前绑定期望的会话标识：旧会话返回的数据按此丢弃。 */
    private volatile String expectedSessionId;
    /** 用户主动暂停标记（窗口隐藏触发的暂停不影响它）。 */
    private volatile boolean userPaused;
    private volatile boolean viewHidden;

    // 选中状态（跨刷新保持）
    private volatile String selectedDiskDevice;
    private volatile String selectedInterface;
    private volatile String selectedProcessIdentity;

    // 表格数据
    private final ObservableList<ProcessInfo> processRows = FXCollections.observableArrayList();
    private FilteredList<ProcessInfo> filteredProcesses;
    private SortedList<ProcessInfo> sortedProcesses;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeCharts();
        initializeDiskTable();
        initializeProcessTable();
        initializeNetworkTable();
        initializeOverviewTables();
        initializeToolbar();

        Platform.runLater(this::setChartsWhiteTheme);
        Platform.runLater(this::registerWindowVisibilityHandling);

        // 监听当前激活 Tab 变化
        if (moduleManager != null) {
            com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters()
                    .addListener((javafx.collections.MapChangeListener<? super String, ? super Object>) change -> {
                        if (change.getKey().equals("current_active_tab") && change.wasAdded()) {
                            String tabId = (String) change.getValueAdded();
                            Platform.runLater(() -> setSystemMonitorForTab(tabId));
                        }
                    });
        }
    }

    @FXML
    public void refreshMonitoring() {
        if (currentSystemMonitor != null) {
            currentSystemMonitor.refreshNow();
        }
    }

    /** 暂停 / 恢复采样（同时停止实时更新；恢复后重建差分基线）。 */
    @FXML
    public void toggleMonitoringPause() {
        if (currentSystemMonitor == null) return;
        userPaused = !userPaused;
        applyPauseState();
    }

    private void applyPauseState() {
        boolean paused = userPaused || viewHidden;
        if (paused) {
            if (currentSystemMonitor != null) currentSystemMonitor.pauseMonitoring();
            if (pauseResumeButton != null) {
                pauseResumeButton.setText(viewHidden && !userPaused ? "已暂停（窗口隐藏）" : "恢复");
            }
        } else {
            if (currentSystemMonitor != null) currentSystemMonitor.resumeMonitoring();
            if (pauseResumeButton != null) pauseResumeButton.setText("暂停");
        }
    }

    /** 窗口隐藏时降低采样：页面不可见时不做无谓的远程采集。 */
    private void registerWindowVisibilityHandling() {
        if (monitorTabPane == null || monitorTabPane.getScene() == null) return;
        monitorTabPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.windowProperty().addListener((obsW, oldWindow, newWindow) -> {
                    if (newWindow != null) {
                        newWindow.showingProperty().addListener((obsS, o, showing) -> updateViewHidden(!showing));
                        if (newWindow instanceof javafx.stage.Stage stage) {
                            stage.iconifiedProperty().addListener((obsI, o, iconified) -> updateViewHidden(iconified));
                        }
                    }
                });
            }
        });
    }

    private void updateViewHidden(boolean hidden) {
        viewHidden = hidden;
        Platform.runLater(this::applyPauseState);
    }

    private void initializeToolbar() {
        if (intervalComboBox != null) {
            intervalComboBox.getItems().setAll("1 秒", "2 秒", "5 秒", "10 秒");
            intervalComboBox.getSelectionModel().select(1);
            intervalComboBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
                long ms = parseIntervalMs(n);
                if (currentSystemMonitor != null) currentSystemMonitor.setMonitorInterval(ms);
            });
        }
        if (trendRangeComboBox != null) {
            trendRangeComboBox.getItems().setAll("最近 1 分钟", "最近 5 分钟", "最近 15 分钟");
            trendRangeComboBox.getSelectionModel().select(0);
            trendRangeComboBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
                trendRangeMs = parseTrendRangeMs(n);
                reconfigureTimeAxes();
            });
        }
        if (pauseResumeButton != null) {
            pauseResumeButton.setText("暂停");
        }
        if (refreshBtn != null) {
            refreshBtn.setOnAction(e -> refreshMonitoring());
        }
        if (monitorTabPane != null) {
            // 进入进程页后启用进程采样；离开时关闭以节省开销
            monitorTabPane.getSelectionModel().selectedItemProperty().addListener((obs, o, selected) -> {
                boolean processActive = selected == processTab;
                if (currentSystemMonitor != null) {
                    currentSystemMonitor.setProcessSamplingEnabled(processActive);
                    if (processActive) currentSystemMonitor.refreshNow();
                }
            });
        }
    }

    private static long parseIntervalMs(String text) {
        if (text == null) return 2000;
        return switch (text) {
            case "1 秒" -> 1000;
            case "5 秒" -> 5000;
            case "10 秒" -> 10000;
            default -> 2000;
        };
    }

    private static long parseTrendRangeMs(String text) {
        if (text == null) return DEFAULT_TREND_RANGE_MS;
        return switch (text) {
            case "最近 5 分钟" -> 300_000L;
            case "最近 15 分钟" -> 900_000L;
            default -> 60_000L;
        };
    }

    private void setChartsWhiteTheme() {
        UITools.setChartWhiteTheme(cpuChart);
        UITools.setChartWhiteTheme(memoryChart);
        UITools.setChartWhiteTheme(diskChart);
        UITools.setChartWhiteTheme(networkChart);
        UITools.setChartWhiteTheme(diskThroughputChart);
        UITools.setChartWhiteTheme(diskIopsChart);
    }

    @SuppressWarnings("DuplicatedCode")
    private void initializeCharts() {
        if (cpuChart != null) {
            cpuSeries = new XYChart.Series<>();
            cpuSeries.setName("CPU使用率");
            cpuChart.getData().add(cpuSeries);
            cpuChart.setCreateSymbols(false);
            configureTimeAxis(cpuXAxis);
            if (cpuYAxis != null) {
                cpuYAxis.setLabel("%");
                cpuYAxis.setUpperBound(100);
            }
        }
        if (memoryChart != null) {
            memorySeries = new XYChart.Series<>();
            memorySeries.setName("内存使用率");
            memoryChart.getData().add(memorySeries);
            memoryChart.setCreateSymbols(false);
            configureTimeAxis(memoryXAxis);
            if (memoryYAxis != null) {
                memoryYAxis.setLabel("%");
                memoryYAxis.setUpperBound(100);
            }
        }
        if (diskChart != null) {
            diskSeries = new XYChart.Series<>();
            diskSeries.setName("磁盘容量使用率");
            diskChart.getData().add(diskSeries);
            diskChart.setCreateSymbols(false);
            configureTimeAxis(diskXAxis);
            if (diskYAxis != null) {
                diskYAxis.setLabel("%");
                diskYAxis.setUpperBound(100);
            }
        }
        if (networkChart != null) {
            networkRxSeries = new XYChart.Series<>();
            networkTxSeries = new XYChart.Series<>();
            networkRxSeries.setName("接收");
            networkTxSeries.setName("发送");
            networkChart.getData().addAll(networkRxSeries, networkTxSeries);
            networkChart.setCreateSymbols(false);
            configureTimeAxis(networkXAxis);
            if (networkYAxis != null) networkYAxis.setLabel("字节/秒");
        }
        if (diskThroughputChart != null) {
            diskReadSeries = new XYChart.Series<>();
            diskWriteSeries = new XYChart.Series<>();
            diskReadSeries.setName("读取");
            diskWriteSeries.setName("写入");
            diskThroughputChart.getData().addAll(diskReadSeries, diskWriteSeries);
            diskThroughputChart.setCreateSymbols(false);
            configureTimeAxis(diskThroughputXAxis);
            if (diskThroughputYAxis != null) diskThroughputYAxis.setLabel("字节/秒");
        }
        if (diskIopsChart != null) {
            diskReadIopsSeries = new XYChart.Series<>();
            diskWriteIopsSeries = new XYChart.Series<>();
            diskReadIopsSeries.setName("读 IOPS");
            diskWriteIopsSeries.setName("写 IOPS");
            diskIopsChart.getData().addAll(diskReadIopsSeries, diskWriteIopsSeries);
            diskIopsChart.setCreateSymbols(false);
            configureTimeAxis(diskIopsXAxis);
            if (diskIopsYAxis != null) diskIopsYAxis.setLabel("操作/秒");
        }
    }

    /**
     * 共享的毫秒时间轴配置：固定主刻度、无次刻度、关闭动画与 forceZeroInRange，
     * 避免 epoch 毫秒范围产生数千刻度。
     */
    private void configureTimeAxis(NumberAxis axis) {
        if (axis == null) return;
        axis.setLabel("时间");
        axis.setAutoRanging(false);
        axis.setForceZeroInRange(false);
        axis.setAnimated(false);
        axis.setTickUnit(TICK_UNIT_BY_RANGE.getOrDefault(trendRangeMs, 10_000.0));
        axis.setMinorTickCount(0);
        axis.setMinorTickVisible(false);
        axis.setTickLabelsVisible(false);
        updateTimeAxis(axis, System.currentTimeMillis());
    }

    /** 按当前趋势范围重配四个概览轴与所有图表轴。 */
    private void reconfigureTimeAxes() {
        reconfigureAxis(cpuXAxis);
        reconfigureAxis(memoryXAxis);
        reconfigureAxis(diskXAxis);
        reconfigureAxis(networkXAxis);
        reconfigureAxis(diskThroughputXAxis);
        reconfigureAxis(diskIopsXAxis);
    }

    private void reconfigureAxis(NumberAxis axis) {
        if (axis == null) return;
        axis.setTickUnit(TICK_UNIT_BY_RANGE.getOrDefault(trendRangeMs, 10_000.0));
    }

    /** 将时间轴移动到当前窗口，保证中间状态不出现 lower &gt;= upper。 */
    private static void updateTimeAxis(NumberAxis axis, long currentTime) {
        if (axis == null) return;
        double newLower = currentTime - trendRangeOf(axis);
        double newUpper = currentTime + CHART_FUTURE_PADDING;
        if (newUpper > axis.getLowerBound()) {
            axis.setUpperBound(newUpper);
            axis.setLowerBound(newLower);
        } else {
            axis.setLowerBound(newLower);
            axis.setUpperBound(newUpper);
        }
    }

    /** 从轴当前窗口反推趋势范围（窗口 = 范围 + 5 秒填充）。 */
    private static long trendRangeOf(NumberAxis axis) {
        double window = axis.getUpperBound() - axis.getLowerBound();
        double range = window - CHART_FUTURE_PADDING;
        return range <= 0 ? DEFAULT_TREND_RANGE_MS : (long) range;
    }

    // ------------------------------------------------------------------------
    // 表格初始化
    // ------------------------------------------------------------------------

    private void initializeDiskTable() {
        if (diskIoTable == null) return;
        diskDeviceColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getDevice()));
        diskReadRateColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                MonitorFormats.formatBytesPerSecond(p.getValue().getReadBytesPerSec())));
        diskWriteRateColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                MonitorFormats.formatBytesPerSecond(p.getValue().getWriteBytesPerSec())));
        diskReadIopsColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleDoubleProperty(p.getValue().getReadIops()));
        diskWriteIopsColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleDoubleProperty(p.getValue().getWriteIops()));
        diskLatencyColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                p.getValue().getAvgRequestMs() == null ? "—" : String.format("%.2f ms", p.getValue().getAvgRequestMs())));
        diskBusyColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                p.getValue().getBusyPercent() <= 0 ? "—" : MonitorFormats.formatPercent(p.getValue().getBusyPercent())));
        diskLevelColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getDeviceLevel()));
        diskMountColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                p.getValue().getMountPoint() == null ? "—" : p.getValue().getMountPoint()));
        diskIoTable.setItems(FXCollections.observableArrayList());
        diskIoTable.getSelectionModel().selectedItemProperty().addListener((obs, o, selected) -> {
            if (selected != null) {
                selectedDiskDevice = selected.getDevice();
                updateDiskDetail(selected);
                refreshDiskTrendCharts();
            }
        });
    }

    private void initializeProcessTable() {
        if (processTable == null) return;
        procPidColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleIntegerProperty(p.getValue().getPid()));
        procNameColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getName()));
        procUserColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getUser()));
        // CPU% 单核满载 100% 口径：允许多线程进程超过 100%
        procCpuColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleDoubleProperty(p.getValue().getCpuPercent()));
        procCpuColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("%.1f%%", item.doubleValue()));
            }
        });
        // 本 JavaFX 版本的 TableColumn 无 setTooltip，口径说明无法以提示承载，统一以代码/注释表达
        procMemColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleDoubleProperty(p.getValue().getMemPercent()));
        procRssColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleLongProperty(p.getValue().getRssKiB()));
        procRssColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : MonitorFormats.formatKiB(item.doubleValue()));
            }
        });
        procStateColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getState()));
        procElapsedColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getElapsed()));
        procPpidColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleIntegerProperty(p.getValue().getPpid()));
        procThreadsColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleIntegerProperty(p.getValue().getThreadCount()));
        procStartColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getStartedAt()));

        filteredProcesses = new FilteredList<>(processRows, p -> true);
        sortedProcesses = new SortedList<>(filteredProcesses);
        sortedProcesses.comparatorProperty().bind(processTable.comparatorProperty());
        processTable.setItems(sortedProcesses);

        if (processSearchField != null) {
            processSearchField.textProperty().addListener((obs, o, text) -> applyProcessFilter());
        }
        if (processStateFilter != null) {
            processStateFilter.getItems().setAll("全部状态", "运行 (R)", "睡眠 (S/D)", "停止 (T)", "僵尸 (Z)");
            processStateFilter.getSelectionModel().select(0);
            processStateFilter.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> applyProcessFilter());
        }
        if (processAutoRefreshCheckBox != null) {
            processAutoRefreshCheckBox.selectedProperty().addListener((obs, o, selected) -> {
                if (currentSystemMonitor != null) {
                    currentSystemMonitor.setProcessSamplingEnabled(selected);
                    if (selected) currentSystemMonitor.refreshNow();
                }
            });
        }
        bindOptionalColumns();
        processTable.getSelectionModel().selectedItemProperty().addListener((obs, o, selected) -> {
            if (selected != null) {
                selectedProcessIdentity = selected.getIdentityKey();
                updateProcessDetail(selected);
            }
        });
    }

    private void bindOptionalColumns() {
        if (showPpidMenuItem != null) {
            showPpidMenuItem.selectedProperty().addListener((obs, o, show) -> {
                if (procPpidColumn != null) procPpidColumn.setVisible(show);
            });
        }
        if (showThreadsMenuItem != null) {
            showThreadsMenuItem.selectedProperty().addListener((obs, o, show) -> {
                if (procThreadsColumn != null) procThreadsColumn.setVisible(show);
            });
        }
        if (showStartMenuItem != null) {
            showStartMenuItem.selectedProperty().addListener((obs, o, show) -> {
                if (procStartColumn != null) procStartColumn.setVisible(show);
            });
        }
    }

    private void applyProcessFilter() {
        if (filteredProcesses == null) return;
        String query = processSearchField == null ? "" : processSearchField.getText().trim().toLowerCase();
        String stateFilter = processStateFilter == null ? "全部状态" : processStateFilter.getValue();
        filteredProcesses.setPredicate(info -> {
            boolean matchesQuery = query.isEmpty()
                    || info.getName().toLowerCase().contains(query)
                    || info.getUser().toLowerCase().contains(query)
                    || Integer.toString(info.getPid()).contains(query)
                    || info.getCommandLine().toLowerCase().contains(query);
            boolean matchesState = matchesStateFilter(info, stateFilter);
            return matchesQuery && matchesState;
        });
    }

    private static boolean matchesStateFilter(ProcessInfo info, String stateFilter) {
        if (stateFilter == null || stateFilter.startsWith("全部")) return true;
        char first = info.getState() == null || info.getState().isEmpty() ? '?' : info.getState().charAt(0);
        return switch (stateFilter) {
            case "运行 (R)" -> first == 'R';
            case "睡眠 (S/D)" -> first == 'S' || first == 'D' || first == 'I';
            case "停止 (T)" -> first == 'T';
            case "僵尸 (Z)" -> first == 'Z';
            default -> true;
        };
    }

    private void initializeNetworkTable() {
        if (networkTable == null) return;
        netNameColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getName()));
        netStateColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                interfaceStateText(p.getValue().getName())));
        netRxRateColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                MonitorFormats.formatBytesPerSecond(p.getValue().getRxBytesPerSec())));
        netTxRateColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                MonitorFormats.formatBytesPerSecond(p.getValue().getTxBytesPerSec())));
        netRxTotalColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                MonitorFormats.formatBytes(p.getValue().getTotalRxBytes())));
        netTxTotalColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                MonitorFormats.formatBytes(p.getValue().getTotalTxBytes())));
        netRxPpsColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleDoubleProperty(p.getValue().getRxPacketsPerSec()));
        netTxPpsColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleDoubleProperty(p.getValue().getTxPacketsPerSec()));
        netErrDropColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(errDropText(p.getValue())));
        networkTable.setItems(FXCollections.observableArrayList());
        if (showVirtualInterfacesCheckBox != null) {
            showVirtualInterfacesCheckBox.selectedProperty().addListener((obs, o, show) -> rebuildInterfaceView());
        }
        networkTable.getSelectionModel().selectedItemProperty().addListener((obs, o, selected) -> {
            if (selected != null) {
                selectedInterface = selected.getName();
                updateNetworkDetail(selected);
                refreshNetworkTrendChart();
            }
        });
    }

    private static String errDropText(NetworkInterfaceRate rate) {
        long err = rate.getRxErrorDelta() + rate.getTxErrorDelta();
        long drop = rate.getRxDropDelta() + rate.getTxDropDelta();
        if (err == 0 && drop == 0) return "0 / 0";
        return "错误 +" + err + " / 丢包 +" + drop;
    }

    private String interfaceStateText(String name) {
        NetworkInterfaceInfo info = lastInterfaceInfo == null ? null : lastInterfaceInfo.get(name);
        if (info == null || info.getOperState() == null) return "未知";
        return info.getOperState();
    }

    private NetworkInterfaceInfo currentInterfaceInfo(String name) {
        return lastInterfaceInfo == null ? null : lastInterfaceInfo.get(name);
    }

    // 概览中两个补充表格
    private void initializeOverviewTables() {
        if (topProcessTable != null) {
            topPidColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleIntegerProperty(p.getValue().getPid()));
            topNameColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getName()));
            topCpuColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleDoubleProperty(p.getValue().getCpuPercent()));
            topMemColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleDoubleProperty(p.getValue().getMemPercent()));
            topProcessTable.setItems(FXCollections.observableArrayList());
        }
        if (overviewDiskTable != null) {
            ovDiskDeviceColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getDevice()));
            ovDiskReadColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                    MonitorFormats.formatBytesPerSecond(p.getValue().getReadBytesPerSec())));
            ovDiskWriteColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                    MonitorFormats.formatBytesPerSecond(p.getValue().getWriteBytesPerSec())));
            ovDiskBusyColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                    p.getValue().getBusyPercent() <= 0 ? "—" : MonitorFormats.formatPercent(p.getValue().getBusyPercent())));
            overviewDiskTable.setItems(FXCollections.observableArrayList());
        }
        if (activeInterfaceTable != null) {
            aiNameColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().getName()));
            aiRxColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                    MonitorFormats.formatBytesPerSecond(p.getValue().getRxBytesPerSec())));
            aiTxColumn.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                    MonitorFormats.formatBytesPerSecond(p.getValue().getTxBytesPerSec())));
            activeInterfaceTable.setItems(FXCollections.observableArrayList());
        }
    }

    // ------------------------------------------------------------------------
    // 会话绑定与连接隔离
    // ------------------------------------------------------------------------

    /**
     * 为指定的Tab设置系统监控器（每次启动都建立新的会话代次）。
     */
    public void setSystemMonitorForTab(String tabId) {
        currentTabId = tabId;
        if (sshService != null && tabId != null) {
            SSHConnectionManager connection = sshService.getTabConnection(tabId);
            if (connection != null && connection.isConnected()) {
                // 同一标签重连后可能换用新的 SSH 连接实例。
                SystemMonitor tabMonitor = tabSystemMonitors.compute(tabId, (id, cached) ->
                        cached != null && cached.isBoundTo(connection) ? cached : new SystemMonitor(connection));
                if (currentSystemMonitor != null && currentSystemMonitor != tabMonitor) {
                    currentSystemMonitor.stopMonitoring();
                }
                currentSystemMonitor = tabMonitor;
                if (processTable != null) {
                    tabMonitor.setProcessSamplingEnabled(monitorTabPane != null
                            && monitorTabPane.getSelectionModel().getSelectedItem() == processTab);
                }
                tabMonitor.setSnapshotCallback(this::onSnapshot);
                tabMonitor.startMonitoring();
                expectedSessionId = tabMonitor.getCurrentSessionId();
                updateHostLabel(tabId);
                applyPauseState();
                log.info("Started monitoring for tab: {}", tabId);
            } else {
                stopCurrentMonitor();
                clearMetricsDisplay();
                awaitConnectionThenBind(tabId);
                log.info("Stopped monitoring for tab: {} (not connected, awaiting retry)", tabId);
            }
        } else {
            stopCurrentMonitor();
            clearMetricsDisplay();
            log.info("Stopped monitoring (invalid parameters)");
        }
    }

    private void stopCurrentMonitor() {
        if (currentSystemMonitor != null) {
            currentSystemMonitor.stopMonitoring();
            currentSystemMonitor = null;
        }
        expectedSessionId = null;
    }

    private void updateHostLabel(String tabId) {
        if (hostLabel == null || sshService == null) return;
        com.runzhi.aishell.ssh.domain.ConnectionParams params = sshService.getTabConnectionParams(tabId);
        String host = params == null ? tabId
                : (params.getUsername() == null ? "" : params.getUsername() + "@") + params.getHost();
        hostLabel.setText("当前主机：" + host);
        if (connectionStateLabel != null) {
            connectionStateLabel.setText("● 已连接");
            connectionStateLabel.getStyleClass().setAll("monitor-status-chip", "status-ok");
        }
    }

    /** 等待目标会话的 SSH 连接就绪后重试绑定。 */
    private void awaitConnectionThenBind(String tabId) {
        if (tabId == null || sshService == null) return;
        final int[] attempts = { 0 };
        retryOnce(tabId, attempts);
    }

    private void retryOnce(String tabId, int[] attempts) {
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));
        pause.setOnFinished(event -> {
            if (!Objects.equals(tabId, currentTabId)) return;
            attempts[0]++;
            SSHConnectionManager connection = sshService.getTabConnection(tabId);
            if (connection != null && connection.isConnected()) {
                setSystemMonitorForTab(tabId);
            } else if (attempts[0] < 20) {
                retryOnce(tabId, attempts);
            }
        });
        pause.play();
    }

    public void removeSystemMonitorForTab(String tabId) {
        SystemMonitor monitor = tabSystemMonitors.remove(tabId);
        if (monitor != null) monitor.stopMonitoring();
        if (monitor == currentSystemMonitor) {
            currentSystemMonitor = null;
            currentTabId = null;
            expectedSessionId = null;
            clearMetricsDisplay();
        }
    }

    /** 清空监控数据显示 */
    public void clearMetricsDisplay() {
        Platform.runLater(() -> {
            cpuProgress.setProgress(0);
            memoryProgress.setProgress(0);
            diskProgress.setProgress(0);
            if (cpuPercentLabel != null) cpuPercentLabel.setText("—");
            if (memoryPercentLabel != null) memoryPercentLabel.setText("—");
            if (diskPercentLabel != null) diskPercentLabel.setText("—");
            if (netRateLabel != null) netRateLabel.setText("—");
            if (netScopeLabel != null) netScopeLabel.setText("统计范围：—");
            if (loadLabel != null) loadLabel.setText("Load: 0.00, 0.00, 0.00");
            if (uptimeLabel != null) uptimeLabel.setText("—");
            if (memoryLabel != null) memoryLabel.setText("—");
            if (swapLabel != null) swapLabel.setText("—");
            if (samplingStateLabel != null) samplingStateLabel.setText("—");
            if (hostLabel != null) hostLabel.setText("当前主机：—");
            if (connectionStateLabel != null) {
                connectionStateLabel.setText("● 未连接");
                connectionStateLabel.getStyleClass().setAll("monitor-status-chip", "status-error");
            }
            if (lastUpdateLabel != null) lastUpdateLabel.setText("最后更新：—");

            if (cpuSeries != null) cpuSeries.getData().clear();
            if (memorySeries != null) memorySeries.getData().clear();
            if (diskSeries != null) diskSeries.getData().clear();
            if (networkRxSeries != null) networkRxSeries.getData().clear();
            if (networkTxSeries != null) networkTxSeries.getData().clear();
            if (diskReadSeries != null) diskReadSeries.getData().clear();
            if (diskWriteSeries != null) diskWriteSeries.getData().clear();
            if (diskReadIopsSeries != null) diskReadIopsSeries.getData().clear();
            if (diskWriteIopsSeries != null) diskWriteIopsSeries.getData().clear();

            cpuHistory.clear();
            memoryHistory.clear();
            diskUsageHistory.clear();
            clearMapHistories(diskThroughputReadHistories);
            clearMapHistories(diskThroughputWriteHistories);
            clearMapHistories(diskIopsReadHistories);
            clearMapHistories(diskIopsWriteHistories);
            clearMapHistories(netRxHistories);
            clearMapHistories(netTxHistories);

            if (diskIoTable != null) diskIoTable.getItems().clear();
            if (networkTable != null) networkTable.getItems().clear();
            if (processRows != null) processRows.clear();
            if (overviewDiskTable != null) overviewDiskTable.getItems().clear();
            if (activeInterfaceTable != null) activeInterfaceTable.getItems().clear();
            if (topProcessTable != null) topProcessTable.getItems().clear();
            if (processDetailLabel != null) processDetailLabel.setText("选择进程查看详情");
            if (diskDetailLabel != null) diskDetailLabel.setText("选择设备查看详情");
            if (networkDetailLabel != null) networkDetailLabel.setText("选择接口查看详情");
        });
    }

    private void clearMapHistories(Map<String, MetricHistory> histories) {
        histories.clear();
    }

    /** 停止所有监控器 */
    public void stopAllMonitors() {
        for (SystemMonitor monitor : tabSystemMonitors.values()) {
            if (monitor != null) monitor.stopMonitoring();
        }
        tabSystemMonitors.clear();
        if (currentSystemMonitor != null) {
            currentSystemMonitor.stopMonitoring();
            currentSystemMonitor = null;
        }
        expectedSessionId = null;
    }

    public SystemMonitor getCurrentSystemMonitor() {
        return currentSystemMonitor;
    }

    // ------------------------------------------------------------------------
    // 快照消费（采样线程 → JavaFX 线程）
    // ------------------------------------------------------------------------

    /** 最近一次接口静态信息（低频通道结果，供表格状态列与详情使用）。 */
    private volatile Map<String, NetworkInterfaceInfo> lastInterfaceInfo;

    private void onSnapshot(MonitorSnapshot snapshot) {
        if (snapshot == null) return;
        // 连接隔离：切换主机/重连后旧会话的数据必须丢弃
        if (expectedSessionId == null || !snapshot.isForSession(expectedSessionId)) {
            return;
        }
        // 发布到参数总线：高级监控图表窗口订阅它
        if (snapshot.getSystemMetrics() != null) {
            com.runzhi.aishell.common.util.ParameterManager.getInstance()
                    .put("last_system_metrics", snapshot.getSystemMetrics());
        }
        Platform.runLater(() -> renderSnapshot(snapshot));
    }

    private void renderSnapshot(MonitorSnapshot snapshot) {
        if (!snapshot.isForSession(expectedSessionId)) return;
        long now = snapshot.getEpochMs();
        if (lastUpdateLabel != null) {
            lastUpdateLabel.setText("最后更新：" + LocalTime.now().format(TIME_FORMAT)
                    + "（耗时 " + snapshot.getDurationMs() + " ms）");
        }

        renderOverview(snapshot, now);
        renderDiskIo(snapshot, now);
        renderNetwork(snapshot, now);
        renderProcesses(snapshot);
    }

    private void renderOverview(MonitorSnapshot snapshot, long now) {
        SystemMetrics metrics = snapshot.getSystemMetrics();
        if (metrics == null) return;

        // 有效零值也必须覆盖旧显示（修复此前 diskUsage>0 才更新的缺陷）
        cpuProgress.setProgress(clampProgress(metrics.getCpuTotalUsed()));
        cpuPercentLabel.setText(snapshot.isCpuFirstSample() ? "采样中"
                : String.format("%.1f%%", metrics.getCpuTotalUsed()));
        if (metrics.getTotalMemory() > 0) {
            double memPct = (double) metrics.getUsedMemory() / metrics.getTotalMemory() * 100;
            memoryProgress.setProgress(memPct / 100.0);
            memoryPercentLabel.setText(String.format("%.1f%%", memPct));
        }
        MetricStatus<List<MountInfo>> mounts = snapshot.getMounts();
        if (mounts != null && mounts.getValue() != null && !mounts.getValue().isEmpty()) {
            double maxUsage = SystemMetricsCollector.maxUsageOf(mounts.getValue());
            diskProgress.setProgress(maxUsage / 100.0);
            diskPercentLabel.setText(MonitorFormats.formatPercent(maxUsage));
        }

        // 网络收发卡：按当前筛选范围汇总，并标明统计范围
        MetricStatus<Map<String, NetworkInterfaceRate>> rates = snapshot.getNetworkRates();
        if (rates != null && rates.getValue() != null) {
            double rx = 0, tx = 0;
            int count = 0;
            for (NetworkInterfaceRate rate : rates.getValue().values()) {
                if (isInterfaceVisible(rate.getName())) {
                    rx += rate.getRxBytesPerSec();
                    tx += rate.getTxBytesPerSec();
                    count++;
                }
            }
            boolean first = rates.getValue().values().stream()
                    .allMatch(r -> r.getState() == SampleState.FIRST_SAMPLE || r.getState() == SampleState.NO_DATA);
            netRateLabel.setText(first ? "采样中" : MonitorFormats.formatBothRates(rx, tx));
            netScopeLabel.setText("已选 " + count + " 个接口"
                    + (showVirtualInterfacesCheckBox != null && showVirtualInterfacesCheckBox.isSelected()
                    ? "（含 lo / 虚拟）" : "（活动接口）"));
        }

        if (loadLabel != null) {
            loadLabel.setText(String.format("Load: %.2f, %.2f, %.2f", metrics.getLoadAverage1(),
                    metrics.getLoadAverage5(), metrics.getLoadAverage15()));
        }
        if (uptimeLabel != null) {
            uptimeLabel.setText(metrics.getUptime() == null ? "—" : metrics.getUptime());
        }
        if (memoryLabel != null && metrics.getTotalMemory() > 0) {
            memoryLabel.setText("内存 " + MonitorFormats.formatKiB(metrics.getUsedMemory())
                    + " / " + MonitorFormats.formatKiB(metrics.getTotalMemory()));
        }
        if (swapLabel != null && metrics.getTotalSwap() > 0) {
            swapLabel.setText("Swap " + MonitorFormats.formatKiB(metrics.getUsedSwap())
                    + " / " + MonitorFormats.formatKiB(metrics.getTotalSwap()));
        }
        if (samplingStateLabel != null) {
            samplingStateLabel.setText(buildSamplingStateText(snapshot));
        }

        // 历史与趋势图（首版使用连接期间内存历史，不跨重启持久化）
        snapshotCpuFirstSampleFlag = snapshot.isCpuFirstSample();
        if (!snapshotCpuFirstSampleFlag) {
            cpuHistory.add(now, metrics.getCpuTotalUsed());
        }
        if (metrics.getTotalMemory() > 0) {
            memoryHistory.add(now, (double) metrics.getUsedMemory() / metrics.getTotalMemory() * 100);
        }
        if (mounts != null && mounts.getValue() != null && !mounts.getValue().isEmpty()) {
            diskUsageHistory.add(now, SystemMetricsCollector.maxUsageOf(mounts.getValue()));
        }
        updateOverviewCharts(metrics, now);
        checkAlerts(metrics);

        // 概览补充表格
        MetricStatus<Map<String, DiskIoRate>> diskIo = snapshot.getDiskIo();
        if (diskIo != null && diskIo.getValue() != null && overviewDiskTable != null) {
            ObservableList<DiskIoRate> physical = FXCollections.observableArrayList(
                    diskIo.getValue().values().stream()
                            .filter(r -> DiskIoCollector.isPhysicalDeviceLevel(r.getDeviceLevel()))
                            .sorted(Comparator.comparing(DiskIoRate::getDevice))
                            .toList());
            overviewDiskTable.setItems(physical);
        }
        if (rates != null && rates.getValue() != null && activeInterfaceTable != null) {
            ObservableList<NetworkInterfaceRate> active = FXCollections.observableArrayList(
                    rates.getValue().values().stream()
                            .filter(r -> !"lo".equals(r.getName())
                                    && !NetworkInterfaceCollector.isLikelyVirtual(r.getName()))
                            .sorted(Comparator.comparing(NetworkInterfaceRate::getName))
                            .toList());
            activeInterfaceTable.setItems(active);
        }
    }

    private static String buildSamplingStateText(MonitorSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        appendSectionState(sb, "系统", snapshot.isCpuFirstSample(), null, null);
        appendSectionState(sb, "容量", false, snapshot.getMounts(), null);
        appendSectionState(sb, "磁盘 I/O", false, snapshot.getDiskIo(), null);
        appendSectionState(sb, "网络", false, snapshot.getNetworkRates(), null);
        appendSectionState(sb, "进程", false, snapshot.getProcesses(), null);
        return sb.toString();
    }

    private static void appendSectionState(StringBuilder sb, String name, boolean firstSample,
                                           MetricStatus<?> status, String reason) {
        if (sb.length() > 0) sb.append("　");
        if (firstSample) {
            sb.append(name).append("：采样中");
            return;
        }
        if (status == null || status.getState() == null) {
            sb.append(name).append("：—");
            return;
        }
        sb.append(name).append("：").append(status.getState().display());
        if (status.getReason() != null && status.getState() != SampleState.OK) {
            sb.append("（").append(status.getReason()).append("）");
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private void updateOverviewCharts(SystemMetrics metrics, long now) {
        if (cpuSeries != null && !snapshotCpuFirstSampleFlag) {
            cpuSeries.getData().add(new XYChart.Data<>(now, metrics.getCpuTotalUsed()));
            trimSeries(cpuSeries);
        }
        if (memorySeries != null && metrics.getTotalMemory() > 0) {
            double memoryUsagePercent = (double) metrics.getUsedMemory() / metrics.getTotalMemory() * 100;
            memorySeries.getData().add(new XYChart.Data<>(now, memoryUsagePercent));
            trimSeries(memorySeries);
        }
        if (diskSeries != null) {
            diskSeries.getData().add(new XYChart.Data<>(now, metrics.getDiskUsage()));
            trimSeries(diskSeries);
        }
        updateTimeAxis(cpuXAxis, now);
        updateTimeAxis(memoryXAxis, now);
        updateTimeAxis(diskXAxis, now);
    }

    /** 上一帧是否 CPU 首次采样（避免把基线帧画成 0%）。 */
    private volatile boolean snapshotCpuFirstSampleFlag;

    private static void trimSeries(XYChart.Series<Number, Number> series) {
        while (series.getData().size() > MAX_HISTORY_POINTS) {
            series.getData().remove(0);
        }
    }

    private static double clampProgress(double percent) {
        if (percent < 0 || Double.isNaN(percent) || Double.isInfinite(percent)) return 0;
        return Math.min(1.0, percent / 100.0);
    }

    // ------------------------------------------------------------------------
    // 磁盘 I/O 视图
    // ------------------------------------------------------------------------

    private void renderDiskIo(MonitorSnapshot snapshot, long now) {
        MetricStatus<Map<String, DiskIoRate>> status = snapshot.getDiskIo();
        if (diskIoTable == null) return;
        if (status == null || status.getValue() == null) {
            return;
        }
        ObservableList<DiskIoRate> rows = FXCollections.observableArrayList(
                status.getValue().values().stream()
                        .sorted(Comparator.comparing(DiskIoRate::getDevice))
                        .toList());
        DiskIoRate previousSelection = diskIoTable.getSelectionModel().getSelectedItem();
        diskIoTable.setItems(rows);
        if (previousSelection != null && status.getValue().containsKey(previousSelection.getDevice())) {
            diskIoTable.getSelectionModel().select(previousSelection);
        }

        // 物理设备汇总，明确范围，避免分区与设备重复累计
        long physical = status.getValue().values().stream()
                .filter(r -> DiskIoCollector.isPhysicalDeviceLevel(r.getDeviceLevel())).count();
        diskScopeLabel.setText("设备共 " + status.getValue().size() + " 个（其中物理设备 " + physical + " 个；速率按设备差分）");

        // 写入有界历史（仅追踪存在的设备）
        evictMissingHistories(diskThroughputReadHistories, status.getValue().keySet());
        evictMissingHistories(diskThroughputWriteHistories, status.getValue().keySet());
        evictMissingHistories(diskIopsReadHistories, status.getValue().keySet());
        evictMissingHistories(diskIopsWriteHistories, status.getValue().keySet());
        for (DiskIoRate rate : status.getValue().values()) {
            if (rate.getState() == SampleState.OK) {
                historyFor(diskThroughputReadHistories, rate.getDevice()).add(now, rate.getReadBytesPerSec());
                historyFor(diskThroughputWriteHistories, rate.getDevice()).add(now, rate.getWriteBytesPerSec());
                historyFor(diskIopsReadHistories, rate.getDevice()).add(now, rate.getReadIops());
                historyFor(diskIopsWriteHistories, rate.getDevice()).add(now, rate.getWriteIops());
            }
        }
        // 默认选中第一个物理设备
        if (selectedDiskDevice == null || !status.getValue().containsKey(selectedDiskDevice)) {
            status.getValue().values().stream()
                    .filter(r -> DiskIoCollector.isPhysicalDeviceLevel(r.getDeviceLevel()))
                    .findFirst()
                    .ifPresentOrElse(rate -> {
                        selectedDiskDevice = rate.getDevice();
                        diskIoTable.getSelectionModel().select(rate);
                    }, () -> {
                        selectedDiskDevice = null;
                    });
        }
        refreshDiskTrendCharts();
    }

    private void evictMissingHistories(Map<String, MetricHistory> histories, java.util.Set<String> keep) {
        histories.keySet().removeIf(name -> !keep.contains(name));
    }

    private MetricHistory historyFor(Map<String, MetricHistory> histories, String name) {
        return histories.computeIfAbsent(name, k -> {
            if (histories.size() > MAX_TRACKED_SERIES) {
                histories.keySet().removeIf(key -> !Objects.equals(key, name));
            }
            return new MetricHistory(MAX_HISTORY_POINTS);
        });
    }

    private void refreshDiskTrendCharts() {
        if (diskReadSeries == null) return;
        String device = selectedDiskDevice;
        if (device == null) {
            diskReadSeries.getData().clear();
            diskWriteSeries.getData().clear();
            diskReadIopsSeries.getData().clear();
            diskWriteIopsSeries.getData().clear();
            return;
        }
        long now = System.currentTimeMillis();
        fillSeries(diskReadSeries, diskThroughputReadHistories.get(device), now);
        fillSeries(diskWriteSeries, diskThroughputWriteHistories.get(device), now);
        fillSeries(diskReadIopsSeries, diskIopsReadHistories.get(device), now);
        fillSeries(diskWriteIopsSeries, diskIopsWriteHistories.get(device), now);
        updateTimeAxis(diskThroughputXAxis, now);
        updateTimeAxis(diskIopsXAxis, now);
    }

    private void fillSeries(XYChart.Series<Number, Number> series, MetricHistory history, long now) {
        series.getData().clear();
        if (history == null || history.isEmpty()) return;
        for (double[] point : history.window(now, trendRangeMs)) {
            series.getData().add(new XYChart.Data<>(point[0], point[1]));
        }
    }

    private void updateDiskDetail(DiskIoRate rate) {
        if (diskDetailLabel == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("设备 ").append(rate.getDevice())
                .append("（").append(rate.getDeviceLevel()).append("）");
        if (rate.getMountPoint() != null) {
            sb.append("　挂载点：").append(rate.getMountPoint());
        }
        sb.append("　累计读取：").append(MonitorFormats.formatBytes(rate.getTotalReadBytes()));
        sb.append("　累计写入：").append(MonitorFormats.formatBytes(rate.getTotalWriteBytes()));
        sb.append("　状态：").append(rate.getState().display());
        if (rate.getReason() != null && rate.getState() != SampleState.OK) {
            sb.append("（").append(rate.getReason()).append("）");
        }
        diskDetailLabel.setText(sb.toString());
    }

    // ------------------------------------------------------------------------
    // 网络接口视图
    // ------------------------------------------------------------------------

    private void rebuildInterfaceView() {
        // 切换筛选时重新汇总概览卡：renderOverview 下一轮自然刷新
        if (networkTable != null && networkTable.getItems() != null) {
            // 触发一次过滤即可（数据在 renderNetwork 中按筛选重建）
        }
    }

    private boolean isInterfaceVisible(String name) {
        if (showVirtualInterfacesCheckBox != null && showVirtualInterfacesCheckBox.isSelected()) {
            return true;
        }
        return !"lo".equals(name) && !NetworkInterfaceCollector.isLikelyVirtual(name);
    }

    private void renderNetwork(MonitorSnapshot snapshot, long now) {
        MetricStatus<Map<String, NetworkInterfaceRate>> status = snapshot.getNetworkRates();
        if (networkTable == null || status == null || status.getValue() == null) return;
        Map<String, NetworkInterfaceInfo> infoMap = snapshot.getNetworkInfo() != null
                ? snapshot.getNetworkInfo().getValue() : null;
        lastInterfaceInfo = infoMap;
        networkTable.refresh(); // 状态列来自 infoMap，需刷新

        List<NetworkInterfaceRate> visible = status.getValue().values().stream()
                .filter(r -> isInterfaceVisible(r.getName()))
                .sorted(Comparator.comparing(NetworkInterfaceRate::getName))
                .toList();
        ObservableList<NetworkInterfaceRate> rows = FXCollections.observableArrayList(visible);
        NetworkInterfaceRate previousSelection = networkTable.getSelectionModel().getSelectedItem();
        networkTable.setItems(rows);
        if (previousSelection != null && status.getValue().containsKey(previousSelection.getName())) {
            networkTable.getSelectionModel().select(previousSelection);
        }

        if (networkScopeLabel != null) {
            networkScopeLabel.setText("显示 " + visible.size() + " / " + status.getValue().size()
                    + " 个接口（桥接、bond 等可能重叠，不按全部接口求和）");
        }

        evictMissingHistories(netRxHistories, status.getValue().keySet());
        evictMissingHistories(netTxHistories, status.getValue().keySet());
        for (NetworkInterfaceRate rate : status.getValue().values()) {
            if (rate.getState() == SampleState.OK) {
                historyFor(netRxHistories, rate.getName()).add(now, rate.getRxBytesPerSec());
                historyFor(netTxHistories, rate.getName()).add(now, rate.getTxBytesPerSec());
            }
        }
        if (selectedInterface == null || !status.getValue().containsKey(selectedInterface)) {
            selectedInterface = visible.stream().findFirst()
                    .map(NetworkInterfaceRate::getName).orElse(null);
            if (selectedInterface != null) {
                for (NetworkInterfaceRate row : rows) {
                    if (row.getName().equals(selectedInterface)) {
                        networkTable.getSelectionModel().select(row);
                        break;
                    }
                }
            }
        }
        refreshNetworkTrendChart();
    }

    private void refreshNetworkTrendChart() {
        if (networkRxSeries == null) return;
        String name = selectedInterface;
        if (name == null) {
            networkRxSeries.getData().clear();
            networkTxSeries.getData().clear();
            return;
        }
        long now = System.currentTimeMillis();
        fillSeries(networkRxSeries, netRxHistories.get(name), now);
        fillSeries(networkTxSeries, netTxHistories.get(name), now);
        updateTimeAxis(networkXAxis, now);
    }

    private void updateNetworkDetail(NetworkInterfaceRate rate) {
        if (networkDetailLabel == null) return;
        NetworkInterfaceInfo info = currentInterfaceInfo(rate.getName());
        StringBuilder sb = new StringBuilder("接口 ").append(rate.getName());
        sb.append("　状态：").append(info == null || info.getOperState() == null ? "未知" : info.getOperState());
        sb.append("　MTU：").append(info == null || info.getMtu() == null ? "未知" : info.getMtu());
        // 链路速率无法获取时显示未知，不计算带宽利用率
        sb.append("　链路速率：").append(info == null || info.getSpeedMbps() == null ? "未知" : info.getSpeedMbps() + " Mbps");
        sb.append("　IPv4：").append(info == null || info.getIpv4Address() == null ? "—" : info.getIpv4Address());
        sb.append("　采样状态：").append(rate.getState().display());
        if (rate.getReason() != null && rate.getState() != SampleState.OK) {
            sb.append("（").append(rate.getReason()).append("）");
        }
        networkDetailLabel.setText(sb.toString());
    }

    // ------------------------------------------------------------------------
    // 进程视图
    // ------------------------------------------------------------------------

    private void renderProcesses(MonitorSnapshot snapshot) {
        MetricStatus<List<ProcessInfo>> status = snapshot.getProcesses();
        if (status == null || status.getValue() == null || processTable == null) return;
        // 刷新时保持搜索、排序与选中项
        ProcessInfo previous = processTable.getSelectionModel().getSelectedItem();
        List<ProcessInfo> rows = new ArrayList<>(status.getValue());
        processRows.setAll(rows);
        if (previous != null) {
            boolean stillPresent = rows.stream()
                    .anyMatch(p -> p.getIdentityKey().equals(previous.getIdentityKey()));
            if (stillPresent) {
                for (ProcessInfo row : rows) {
                    if (row.getIdentityKey().equals(previous.getIdentityKey())) {
                        processTable.getSelectionModel().select(row);
                        break;
                    }
                }
            } else if (processDetailLabel != null) {
                // 选中目标已退出（按字段/目标降级，不使整张表失败）
                processDetailLabel.setText("选中进程 " + previous.getPid() + "（" + previous.getName()
                        + "）已退出，请重新选择。");
            }
        }
        // 概览 Top 进程（按 CPU 降序，取前 8）
        if (topProcessTable != null) {
            List<ProcessInfo> top = rows.stream()
                    .sorted(Comparator.comparingDouble(ProcessInfo::getCpuPercent).reversed())
                    .limit(8)
                    .toList();
            topProcessTable.setItems(FXCollections.observableArrayList(top));
        }
    }

    private void updateProcessDetail(ProcessInfo info) {
        if (processDetailLabel == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("PID ").append(info.getPid());
        sb.append("　进程名：").append(info.getName());
        sb.append("　用户：").append(info.getUser());
        sb.append("　父进程：").append(info.getPpid() >= 0 ? info.getPpid() : "—");
        sb.append("　状态：").append(info.getState());
        sb.append("　线程数：").append(info.getThreadCount() >= 0 ? info.getThreadCount() : "—");
        sb.append("　启动时间：").append(info.getStartedAt().isEmpty() ? "—" : info.getStartedAt());
        sb.append("　运行时长：").append(info.getElapsed().isEmpty() ? "—" : info.getElapsed());
        sb.append("　CPU：").append(String.format("%.1f%%", info.getCpuPercent()))
                .append("（Δ").append(String.format("%+.1f", info.getCpuDelta())).append("）");
        sb.append("　内存：").append(String.format("%.1f%%", info.getMemPercent()))
                .append("　RSS：").append(MonitorFormats.formatKiB(info.getRssKiB()))
                .append("（Δ").append(String.format("%+d", info.getRssDeltaKiB())).append(" KiB）");
        sb.append("\n命令行：").append(info.getCommandLine());
        processDetailLabel.setText(sb.toString());
        // 工作目录可获得的按需加载
        loadWorkingDirectory(info);
    }

    /** 按需加载选中进程的工作目录（远端能力不足时降级）。 */
    private void loadWorkingDirectory(ProcessInfo info) {
        if (sshService == null || currentTabId == null) return;
        SSHConnectionManager connection = sshService.getTabConnection(currentTabId);
        if (connection == null || !connection.isConnected()) return;
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            String result = null;
            try {
                // pid 为整数，来源于已解析的选择行；readlink 为预定义只读命令
                result = connection.executeCommand("readlink /proc/" + info.getPid() + "/cwd");
            } catch (Exception e) {
                log.debug("Could not read working directory for pid {}: {}", info.getPid(), e.getMessage());
            }
            final String cwd = result == null || result.isBlank() ? null : result.trim();
            Platform.runLater(() -> {
                if (!Objects.equals(selectedProcessIdentity, info.getIdentityKey())) return;
                if (processDetailLabel != null) {
                    String current = processDetailLabel.getText();
                    processDetailLabel.setText(current + "\n工作目录："
                            + (cwd == null ? "不可获得（权限不足或进程已退出）" : cwd));
                }
            });
        });
    }

    @FXML
    public void copySelectedPid() {
        ProcessInfo selected = processTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfoAlert("请先在进程表中选择一个进程");
            return;
        }
        copyToClipboard(Integer.toString(selected.getPid()));
    }

    @FXML
    public void copySelectedCommandLine() {
        ProcessInfo selected = processTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfoAlert("请先在进程表中选择一个进程");
            return;
        }
        copyToClipboard(selected.getCommandLine());
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ------------------------------------------------------------------------
    // 告警（沿用既有阈值样式逻辑）
    // ------------------------------------------------------------------------

    private void checkAlerts(SystemMetrics metrics) {
        checkCpuAlert(metrics.getCpuTotalUsed());
        long totalMem = metrics.getTotalMemory();
        double memoryUsagePercent = totalMem > 0 ? (double) metrics.getUsedMemory() / totalMem * 100 : 0;
        checkMemoryAlert(memoryUsagePercent);
        checkDiskAlert(metrics.getDiskUsage());
    }

    private void checkCpuAlert(double cpuUsage) {
        if (cpuUsage >= CPU_CRITICAL_THRESHOLD) {
            updateProgressBarStyle(cpuProgress, "critical");
        } else if (cpuUsage >= CPU_WARNING_THRESHOLD) {
            updateProgressBarStyle(cpuProgress, "warning");
        } else {
            updateProgressBarStyle(cpuProgress, "normal");
        }
    }

    private void checkMemoryAlert(double memoryUsage) {
        if (memoryUsage >= MEMORY_CRITICAL_THRESHOLD) {
            updateProgressBarStyle(memoryProgress, "critical");
        } else if (memoryUsage >= MEMORY_WARNING_THRESHOLD) {
            updateProgressBarStyle(memoryProgress, "warning");
        } else {
            updateProgressBarStyle(memoryProgress, "normal");
        }
    }

    private void checkDiskAlert(double diskUsage) {
        if (diskUsage >= DISK_CRITICAL_THRESHOLD) {
            updateProgressBarStyle(diskProgress, "critical");
        } else if (diskUsage >= DISK_WARNING_THRESHOLD) {
            updateProgressBarStyle(diskProgress, "warning");
        } else {
            updateProgressBarStyle(diskProgress, "normal");
        }
    }

    private void updateProgressBarStyle(ProgressBar progressBar, String status) {
        if (progressBar == null) return;
        progressBar.getStyleClass().removeAll("progress-normal", "progress-warning", "progress-critical");
        switch (status) {
            case "critical" -> progressBar.getStyleClass().add("progress-critical");
            case "warning" -> progressBar.getStyleClass().add("progress-warning");
            default -> progressBar.getStyleClass().add("progress-normal");
        }
    }

    // ------------------------------------------------------------------------
    // 兼容入口：保留 updateCharts(SystemMetrics) 时间轴行为（回归测试使用）
    // ------------------------------------------------------------------------

    /**
     * 把指标写入四个概览趋势图并推进时间轴。保留给回归测试与外部一次性调用。
     */
    private void updateCharts(SystemMetrics metrics) {
        long currentTime = System.currentTimeMillis();
        if (cpuSeries != null) {
            cpuSeries.getData().add(new XYChart.Data<>(currentTime, metrics.getCpuUser()));
            trimSeries(cpuSeries);
        }
        if (memorySeries != null && metrics.getTotalMemory() > 0) {
            double memoryUsagePercent = (double) metrics.getUsedMemory() / metrics.getTotalMemory() * 100;
            memorySeries.getData().add(new XYChart.Data<>(currentTime, memoryUsagePercent));
            trimSeries(memorySeries);
        }
        if (diskSeries != null) {
            diskSeries.getData().add(new XYChart.Data<>(currentTime, metrics.getDiskUsage()));
            trimSeries(diskSeries);
        }
        updateTimeAxis(cpuXAxis, currentTime);
        updateTimeAxis(memoryXAxis, currentTime);
        updateTimeAxis(diskXAxis, currentTime);
        updateTimeAxis(networkXAxis, currentTime);
    }
}
