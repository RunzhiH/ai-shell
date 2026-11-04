package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.TitledPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real monitor FXML: epoch-ms axes must keep a bounded 10-second tick unit through updates and jumps. */
class MonitorPanelTimeAxisTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void fourEpochTimeAxesKeepBoundedTicksAcrossUpdatesAndClockJumps() throws Exception {
        fx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/runzhi/aishell/monitor-panel.fxml"), I18n.bundle());
            Parent root = loader.load();
            MonitorPanelController controller = loader.getController();
            Scene scene = new Scene(root, 1216, 800);
            String css = getClass().getResource("/com/runzhi/aishell/style.css").toExternalForm();
            scene.getStylesheets().add(css);
            root.resize(1216, 800);
            root.applyCss();
            TitledPane details = (TitledPane) root.lookup(".detailed-monitor");
            assertNotNull(details, "monitor details pane must exist");
            details.setAnimated(false);
            details.setExpanded(true);
            root.layout();
            scene.snapshot(null);

            List<NumberAxis> axes = List.of(
                    (NumberAxis) loader.getNamespace().get("cpuXAxis"),
                    (NumberAxis) loader.getNamespace().get("memoryXAxis"),
                    (NumberAxis) loader.getNamespace().get("diskXAxis"),
                    (NumberAxis) loader.getNamespace().get("networkXAxis"));
            SystemMetrics metrics = SystemMetrics.builder()
                    .cpuUser(10)
                    .totalMemory(1000)
                    .usedMemory(500)
                    .diskUsage(30)
                    .netUp("1 KB/s")
                    .netDown("2 KB/s")
                    .build();

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            PrintStream capture = null;
            try {
                capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
                System.setErr(capture);
                for (int i = 0; i < 6; i++) {
                    metrics.setCpuUser(10 + i * 7);
                    metrics.setUsedMemory(500 + i * 10);
                    metrics.setDiskUsage(30 + i * 2);
                    metrics.setNetUp((i + 1) + " KB/s");
                    metrics.setNetDown((i + 2) + " KB/s");
                    updateCharts(controller, metrics);
                    root.applyCss();
                    root.layout();
                    for (NumberAxis axis : axes) assertBoundedTimeAxis(axis);
                }

                // Simulate a large forward jump from an old visible window.
                long now = System.currentTimeMillis();
                for (NumberAxis axis : axes) {
                    axis.setLowerBound(now - 10 * 60_000L);
                    axis.setUpperBound(now - 9 * 60_000L);
                }
                updateCharts(controller, metrics);
                root.applyCss();
                root.layout();
                for (NumberAxis axis : axes) assertBoundedTimeAxis(axis);

                // Simulate a large backward jump from a future visible window.
                now = System.currentTimeMillis();
                for (NumberAxis axis : axes) {
                    axis.setLowerBound(now + 10 * 60_000L);
                    axis.setUpperBound(now + 11 * 60_000L);
                }
                updateCharts(controller, metrics);
                root.applyCss();
                root.layout();
                for (NumberAxis axis : axes) assertBoundedTimeAxis(axis);
            } finally {
                System.setErr(originalErr);
                if (capture != null) capture.close();
            }

            String warnings = captured.toString(StandardCharsets.UTF_8);
            assertFalse(warnings.contains("more than 2000 major"),
                    "NumberAxis emitted excessive major ticks:\n" + warnings);
            assertFalse(warnings.contains("more than 10000 minor"),
                    "NumberAxis emitted excessive minor ticks:\n" + warnings);
        });
    }

    private static void assertBoundedTimeAxis(NumberAxis axis) {
        assertNotNull(axis);
        assertFalse(axis.isAutoRanging(), "time axis must use the explicit 65s window");
        assertFalse(axis.isForceZeroInRange(), "epoch-ms axis must not include zero");
        assertFalse(axis.getAnimated(), "axis animation can animate stale tick ranges");
        assertEquals(10_000d, axis.getTickUnit(), 0.001d, "10-second major tick unit");
        assertEquals(0, axis.getMinorTickCount(), "minor ticks must be disabled");
        assertFalse(axis.isMinorTickVisible());

        double lower = axis.getLowerBound();
        double upper = axis.getUpperBound();
        assertTrue(lower > 1_000_000_000_000d, "expected epoch milliseconds, lower=" + lower);
        assertEquals(65_000d, upper - lower, 1.0d, "window must stay at 65 seconds");

        long now = System.currentTimeMillis();
        assertTrue(lower <= now - 55_000 && lower >= now - 70_000,
                "lower bound outside the current 65s window: " + lower);
        assertTrue(upper >= now - 5_000 && upper <= now + 15_000,
                "upper bound outside the current 65s window: " + upper);
        int expectedMajorTicks = (int) Math.ceil((upper - lower) / axis.getTickUnit()) + 2;
        assertTrue(expectedMajorTicks <= 12, "too many major ticks: " + expectedMajorTicks);
        assertFalse(axis.getTickMarks().isEmpty(), "axis has not laid out its tick marks");
        assertTrue(axis.getTickMarks().size() <= 40,
                "tick mark list is unbounded: " + axis.getTickMarks().size());
    }

    private static void updateCharts(MonitorPanelController controller, SystemMetrics metrics) {
        try {
            Method method = MonitorPanelController.class.getDeclaredMethod("updateCharts", SystemMetrics.class);
            method.setAccessible(true);
            method.invoke(controller, metrics);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
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
