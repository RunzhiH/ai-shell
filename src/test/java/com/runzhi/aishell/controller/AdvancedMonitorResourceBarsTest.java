package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资源分析视图修正测试（方案 §2 改造点 5）：CPU/内存/磁盘/Swap 不再混合进同一饼图，
 * 改为各自独立的资源条；进程状态分布保留饼图。
 */
class AdvancedMonitorResourceBarsTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void resourceBarsReplaceMixedDenominatorPieChart() throws Exception {
        AtomicReference<FXMLLoader> loaderRef = new AtomicReference<>();
        AtomicReference<AdvancedMonitorChartsController> controllerRef = new AtomicReference<>();
        // 加载与更新在 FX 线程执行（等同参数总线驱动的路径）
        fx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/runzhi/aishell/advanced-monitor-charts.fxml"), I18n.bundle());
            Parent root = loader.load();
            loaderRef.set(loader);
            controllerRef.set(loader.getController());
            SystemMetrics metrics = SystemMetrics.builder()
                    .cpuIdle(70)          // 总使用率 30%
                    .cpuTotalUsed(30)
                    .totalMemory(2000000)
                    .usedMemory(1000000)
                    .diskUsage(45)
                    .totalSwap(1000000)
                    .usedSwap(250000)
                    .totalTasks(120)
                    .runningTasks(2)
                    .sleepingTasks(115)
                    .stoppedTasks(1)
                    .zombieTasks(2)
                    .build();
            controllerRef.get().updateResourceBars(metrics);
        });
        waitForFx(); // 排空 updateResourceBars 内部的 Platform.runLater（在测试线程等待，避免 FX 线程自死锁）
        fx(() -> {
            FXMLLoader loader = loaderRef.get();
            // TitledPane 内容在挂皮肤前不可被场景图 lookup 遍历：直接从命名空间断言
            javafx.scene.layout.VBox resourceBarsBox = (javafx.scene.layout.VBox) loader.getNamespace().get("resourceBarsBox");
            assertNotNull(resourceBarsBox, "资源条容器必须存在");
            List<ProgressBar> bars = resourceBarsBox.getChildren().stream()
                    .map(node -> (ProgressBar) ((javafx.scene.layout.HBox) node).getChildren().get(1))
                    .toList();
            assertEquals(4, bars.size(), "应有四个独立资源条（CPU/内存/磁盘/Swap）");
            assertEquals(0.30, bars.get(0).getProgress(), 0.001, "CPU 条 = 30%");
            assertEquals(0.50, bars.get(1).getProgress(), 0.001, "内存条 = 50%");
            assertEquals(0.45, bars.get(2).getProgress(), 0.001, "磁盘条 = 45%");
            assertEquals(0.25, bars.get(3).getProgress(), 0.001, "Swap 条 = 25%");

            List<String> valueTexts = resourceBarsBox.getChildren().stream()
                    .map(node -> ((javafx.scene.control.Label) ((javafx.scene.layout.HBox) node).getChildren().get(2)).getText())
                    .toList();
            assertTrue(valueTexts.stream().anyMatch(text -> text != null && text.contains("%")),
                    "每条资源应展示当前百分比: " + valueTexts);

            // 进程状态分布饼图保留（共同分母：进程总数），且本次更新填充了四类状态
            javafx.scene.chart.PieChart pie = (javafx.scene.chart.PieChart) loader.getNamespace().get("processTypePieChart");
            assertNotNull(pie, "进程状态分布饼图应保留");
            assertEquals(4, pie.getData().size(), "运行/睡眠/停止/僵尸四类");
            assertEquals(2, pie.getData().get(0).getPieValue(), "运行数");
            assertEquals(115, pie.getData().get(1).getPieValue(), "睡眠数");
        });
    }

    @Test
    void zeroAndMissingValuesShowPlaceholderNotZero() throws Exception {
        AtomicReference<FXMLLoader> loaderRef = new AtomicReference<>();
        AtomicReference<AdvancedMonitorChartsController> controllerRef = new AtomicReference<>();
        fx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/runzhi/aishell/advanced-monitor-charts.fxml"), I18n.bundle());
            loader.load();
            loaderRef.set(loader);
            controllerRef.set(loader.getController());
            // 有效零值：内存 0% 应显示 0.0%（有总量时）
            SystemMetrics zeroMem = SystemMetrics.builder()
                    .cpuTotalUsed(0)
                    .totalMemory(1000)
                    .usedMemory(0)
                    .diskUsage(0)
                    .totalSwap(0)
                    .build();
            controllerRef.get().updateResourceBars(zeroMem);
        });
        waitForFx();
        fx(() -> {
            javafx.scene.layout.VBox resourceBarsBox = (javafx.scene.layout.VBox) loaderRef.get().getNamespace().get("resourceBarsBox");
            List<ProgressBar> bars = resourceBarsBox.getChildren().stream()
                    .map(node -> (ProgressBar) ((javafx.scene.layout.HBox) node).getChildren().get(1))
                    .toList();
            assertEquals(0.0, bars.get(0).getProgress(), 0.001, "CPU 有效零值显示 0");
            assertEquals(0.0, bars.get(1).getProgress(), 0.001, "内存有效零值显示 0");
            // Swap 未启用应显示「未启用」
            List<String> valueTexts = resourceBarsBox.getChildren().stream()
                    .map(node -> ((javafx.scene.control.Label) ((javafx.scene.layout.HBox) node).getChildren().get(2)).getText())
                    .toList();
            assertTrue(valueTexts.contains("未启用"), "Swap 未启用应显示占位而非 0.0%: " + valueTexts);
        });
    }

    /** 排空 FX 事件队列并等待待执行的 Platform.runLater 完成。 */
    private static void waitForFx() throws Exception {
        fx(() -> {
        });
    }

    private static void fx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
