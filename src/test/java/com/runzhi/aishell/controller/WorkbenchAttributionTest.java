package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentActivityBus;
import com.runzhi.aishell.agent.AgentSessionTracker;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.util.ParameterManager;
import com.runzhi.aishell.common.util.TabIdentity;
import com.runzhi.aishell.service.TerminalTabService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ui.CommandPalette;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.event.Event;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A/B/C 会话归属回归（v6 阶段 2，含复审修正）：稳定身份、真实创建入口元数据、
 * 关闭保护、审批核销、目标失效不重路由、停止范围、overlay 宽度。
 * 使用不带内容的空白 Tab，避免 headless 下加载 terminal-tab.fxml。
 */
class WorkbenchAttributionTest {

    private TerminalPanelController controller;
    private TabPane pane;
    private AgentWorkspaceBridge sessionBridge;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @BeforeEach
    void loadTerminalPanel() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<TerminalPanelController> captured = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-panel.fxml"));
                loader.setResources(I18n.bundle());
                loader.load();
                captured.set(loader.getController());
            } catch (Exception failure) {
                failure.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "terminal-panel.fxml load timed out");
        controller = captured.get();
        assertNotNull(controller, "TerminalPanelController 未注入");
        // SplitPane 在未贴 skin 前 lookup() 遍历不到子节点，直接从控制器取字段
        pane = (TabPane) fieldOf(TerminalPanelController.class, "terminalTabPane").get(controller);
        assertNotNull(pane, "terminalTabPane 未注入");
        Field bridgeField = fieldOf(TerminalPanelController.class, "sessionBridge");
        sessionBridge = (AgentWorkspaceBridge) bridgeField.get(controller);
        controller.setBlockedCloseHandler(reason -> { });
    }

    private Field fieldOf(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Tab bareTab(String baseTitle) {
        Tab tab = new Tab(baseTitle);
        tab.setId(TabIdentity.nextTabId());
        tab.getProperties().put(TerminalPanelController.BASE_TITLE_KEY, baseTitle);
        return tab;
    }

    private void waitForFxEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    /**
     * TabPane 的 ObservableList 必须在 FX 线程上变更，否则内部变更器会偶发越界。
     * 不抛出受检异常；FX 线程内的断言失败会被捕获并重抛到测试线程。
     */
    private void runOnFx(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "runOnFx 执行超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("runOnFx 被中断");
        }
        if (failure.get() != null) {
            throw new AssertionError("FX 线程内断言/异常失败", failure.get());
        }
    }

    /** 经 tracker 记录一条带审批句柄的提议（proposalId 由调用方指定）。 */
    private void publishApproval(String sessionId, String proposalId, Consumer<Boolean> handler) {
        AgentSessionTracker.getInstance().record(new AgentActivityBus.Event(
                sessionId, AgentActivityBus.Kind.PROPOSAL, "[中风险] cmd", Instant.now(),
                List.of(), null, handler, proposalId));
    }

    @Test
    void tabIdsAreUniqueAndNotReusedAfterClose() {
        Tab a = bareTab("prod-web-01 · 1");
        Tab b = bareTab("prod-api-01 · 2");
        assertNotEquals(a.getId(), b.getId());
        runOnFx(() -> pane.getTabs().addAll(a, b));

        runOnFx(() -> pane.getTabs().remove(a));
        Tab reopened = bareTab("prod-web-01 · 1");
        runOnFx(() -> pane.getTabs().add(reopened));
        assertNotEquals(a.getId(), reopened.getId());
        assertNotEquals(b.getId(), reopened.getId());
        assertEquals(1, pane.getTabs().stream().filter(tab -> tab == b).count());
    }

    @Test
    void realCreationPathUsesStableIdentityMetadataAndCloseProtection() {
        // 复审修正：真实创建入口（侧栏激活/连接）必须统一完成身份、元数据与关闭保护
        ConnectionParams params = new ConnectionParams();
        params.setHost("10.0.0.20");
        params.setPort(22);
        params.setUsername("root");
        params.setProfileName("staging-01");

        Tab first = controller.createConfiguredTerminalTab(params);
        Tab second = controller.createConfiguredTerminalTab(params);
        assertNotEquals(first.getId(), second.getId(), "真实创建路径不得复用 id");
        assertEquals(params, first.getProperties().get(TerminalPanelController.CONNECTION_PARAMS_KEY));
        assertEquals("staging-01 · " + first.getId().substring(4),
                first.getProperties().get(TerminalPanelController.BASE_TITLE_KEY));

        // 皮肤层关闭请求经过审批守卫
        AtomicInteger decisions = new AtomicInteger();
        Consumer<Boolean> handler = approved -> decisions.incrementAndGet();
        publishApproval(first.getId(), "p1", handler);
        Event blocked = new Event(Tab.TAB_CLOSE_REQUEST_EVENT);
        first.getOnCloseRequest().handle(blocked);
        assertTrue(blocked.isConsumed(), "有待审批时皮肤层关闭应被消费掉");

        // 决策后放行
        AgentSessionTracker.getInstance().trackDecision(first.getId(), "p1", handler).accept(false);
        Event allowed = new Event(Tab.TAB_CLOSE_REQUEST_EVENT);
        first.getOnCloseRequest().handle(allowed);
        assertFalse(allowed.isConsumed());
    }

    @Test
    void closingTabWithPendingApprovalIsBlockedThenAllowed() {
        Tab c = bareTab("staging-01 · 3");
        runOnFx(() -> pane.getTabs().add(c));
        AgentSessionTracker tracker = AgentSessionTracker.getInstance();
        AtomicInteger decisions = new AtomicInteger();
        Consumer<Boolean> handler = approved -> decisions.incrementAndGet();

        publishApproval(c.getId(), "p2", handler);
        assertEquals(1, tracker.pendingApprovals(c.getId()));

        // 有待审批：关闭被阻止
        assertFalse(controller.closeTab(c), "待审批标签不应被关闭");
        assertTrue(pane.getTabs().contains(c));

        // 提交决策（拒绝）后：待审批清零，允许关闭
        tracker.trackDecision(c.getId(), "p2", handler).accept(false);
        assertEquals(1, decisions.get(), "决策句柄应被回调一次");
        assertEquals(0, tracker.pendingApprovals(c.getId()));
        AtomicBoolean closed = new AtomicBoolean();
        runOnFx(() -> closed.set(controller.closeTab(c)));
        assertTrue(closed.get());
        assertFalse(pane.getTabs().contains(c));
    }

    @Test
    void normalTabClosesAndClearsSessionState() {
        Tab normal = bareTab("prod-db-01 · 4");
        runOnFx(() -> pane.getTabs().add(normal));
        AgentSessionTracker.getInstance().markRunning(normal.getId());
        AtomicBoolean normalClosed = new AtomicBoolean();
        runOnFx(() -> normalClosed.set(controller.closeTab(normal)));
        assertTrue(normalClosed.get());
        assertFalse(AgentSessionTracker.getInstance().isRunning(normal.getId()));
    }

    @Test
    void tabBadgeTracksAgentStateForItsOwnSessionOnly() throws Exception {
        Tab b = bareTab("prod-api-01 · 2");
        Tab c = bareTab("staging-01 · 3");
        runOnFx(() -> pane.getTabs().addAll(b, c));

        AtomicInteger decisions = new AtomicInteger();
        Consumer<Boolean> handler = approved -> decisions.incrementAndGet();
        publishApproval(c.getId(), "p3", handler);
        waitForFxEvents();

        // 只有目标会话标签带 Agent 角标，活动 tab b 不受影响
        assertEquals("staging-01 · 3 · Agent", c.getText());
        assertEquals("prod-api-01 · 2", b.getText());

        AgentSessionTracker.getInstance().trackDecision(c.getId(), "p3", handler).accept(true);
        waitForFxEvents();
        assertEquals("staging-01 · 3", c.getText());
        assertEquals("prod-api-01 · 2", b.getText());
    }

    @Test
    void activationSelectsExistingTabForSameConnection() {
        ConnectionParams params = new ConnectionParams();
        params.setHost("10.0.0.20");
        params.setPort(22);
        params.setUsername("root");
        params.setPlainPassword("x");
        params.setProfileName("staging-01");

        Tab existing = controller.createConfiguredTerminalTab(params);
        runOnFx(() -> pane.getTabs().add(existing));
        runOnFx(() -> pane.getTabs().add(bareTab("prod-web-01 · 1")));

        // 激活同一连接：选中既有标签，不新建
        runOnFx(() -> controller.activateConnection(params));
        assertEquals(existing, pane.getSelectionModel().getSelectedItem());
        assertEquals(2, pane.getTabs().stream().filter(tab -> !"terminal-hint-tab".equals(tab.getId())).count());
    }

    @Test
    void insertCommandDoesNotFallBackToCurrentTab() throws Exception {
        // 复审修正：目标会话不存在时不得串到当前标签
        Tab current = bareTab("prod-api-01 · 2");
        runOnFx(() -> pane.getTabs().add(current));
        runOnFx(() -> pane.getSelectionModel().select(current));
        TextField currentInput = new TextField("原内容");
        TerminalTabService tabService = (TerminalTabService) fieldOf(TerminalPanelController.class, "terminalTabService").get(controller);
        tabService.getTabToInput().put(current, currentInput);

        Boolean filled = sessionBridge.insertCommand().apply("不存在的会话", "rm -rf /");
        assertFalse(filled, "目标会话失效应返回 false");
        assertEquals("原内容", currentInput.getText(), "命令不得回退填入当前标签");
    }

    @Test
    void insertCommandFillsLocalSessionTab() throws Exception {
        // 本地终端标签（无连接参数）仍可正常填入
        Tab local = controller.createConfiguredTerminalTab(null);
        runOnFx(() -> pane.getTabs().add(local));
        TextField input = new TextField("");
        TerminalTabService tabService = (TerminalTabService) fieldOf(TerminalPanelController.class, "terminalTabService").get(controller);
        tabService.getTabToInput().put(local, input);

        Boolean filled = sessionBridge.insertCommand().apply(local.getId(), "systemctl status app");
        assertTrue(filled);
        assertEquals("systemctl status app", input.getText());
    }

    @Test
    void overlayLimitsAgentWidthAndSplitRestores() throws Exception {
        // 复审修正：StackPane 会把可伸缩子节点拉满，overlay 必须限制 max
        VBox host = (VBox) fieldOf(TerminalPanelController.class, "agentWorkspaceHost").get(controller);

        runOnFx(() -> {
            // Geometry checks require an open panel, independently of the user's saved toggle state.
            host.setVisible(true);
            host.setManaged(true);
            controller.applyWorkspaceLayout(1600);
            controller.applyWorkspaceLayout(1280);
            assertEquals(352, host.getPrefWidth(), 0.001, "overlay 宽度应为 352px");
            assertEquals(352, host.getMaxWidth(), 0.001, "overlay 必须限制 max，否则会被 StackPane 拉满");
            controller.applyWorkspaceLayout(1600);
            assertEquals(Region.USE_COMPUTED_SIZE, host.getMaxWidth(), "回到分栏应恢复可伸缩约束");
        });
    }

    @Test
    void stopAgentCancelsOnlyTheDisplayedSession() {
        // 复审修正：普通停止只作用于明确的任务会话，不能全局取消
        AgentWorkspaceController agent = new AgentWorkspaceController();
        Tab displayed = bareTab("staging-01 · 3");
        List<String> cancelled = new ArrayList<>();
        AgentSessionTracker tracker = AgentSessionTracker.getInstance();

        // 桥接模拟真实业务侧：取消后由服务发布 CANCELLED 事件（真实路径见 TerminalCommandServiceImpl.cancelAIRequest）
        Consumer<String> cancelSession = sessionId -> {
            cancelled.add(sessionId);
            tracker.record(new AgentActivityBus.Event(sessionId, AgentActivityBus.Kind.CANCELLED,
                    "stopped", Instant.now(), List.of(), null, null, null));
        };
        agent.configure(() -> displayed, null, new AgentWorkspaceBridge((s, c) -> false, cancelSession));

        tracker.markRunning("review-A");
        tracker.markRunning(displayed.getId());
        tracker.record(new AgentActivityBus.Event(displayed.getId(), AgentActivityBus.Kind.PROPOSAL,
                "p5", Instant.now(), List.of(), null, ignored -> { }, "p5"));

        agent.stopAgent();

        assertEquals(List.of(displayed.getId()), cancelled, "/stop 只取消当前显示会话，不得扫描全部");
        assertFalse(tracker.isRunning(displayed.getId()));
        assertTrue(tracker.isRunning("review-A"), "A 会话的运行任务必须保持");
        assertEquals(0, tracker.pendingApprovals(displayed.getId()), "取消后该会话待审批须清零");

        tracker.clear(displayed.getId());
        tracker.clear("review-A");
    }

    @Test
    void commandPaletteOpensOnlyByExplicitShow() throws Exception {
        CommandPalette palette = new CommandPalette(List.of(
                new CommandPalette.Item("终端", "视图", () -> { }),
                new CommandPalette.Item("设置", "文件", () -> { })));
        assertFalse(palette.isShowing(), "面板创建后不得自动打开");

        CountDownLatch shown = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage stage = new Stage();
            stage.show();
            palette.show(stage, 40, 40);
            shown.countDown();
        });
        assertTrue(shown.await(5, TimeUnit.SECONDS));
        assertTrue(palette.isShowing(), "显式 show() 后应打开");
        CountDownLatch hidden = new CountDownLatch(1);
        Platform.runLater(() -> {
            palette.hide();
            hidden.countDown();
        });
        assertTrue(hidden.await(5, TimeUnit.SECONDS));
        assertFalse(palette.isShowing());
    }

    @Test
    void terminalShortcutsDoNotClashWithPalette() {
        // 状态页规范：命令面板本期仅点击入口；终端 Ctrl+K / Ctrl+L / Ctrl+Shift+K 不被抢占
        var defaults = com.runzhi.aishell.common.config.ShortcutConfigStore.defaults();
        assertFalse(defaults.containsValue("Ctrl+K"));
        assertFalse(defaults.containsValue("Ctrl+Shift+K"));
    }
}
