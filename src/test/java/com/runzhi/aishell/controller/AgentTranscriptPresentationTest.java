package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.util.ThemeManager;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX transcript rendering for the narrow Agent workspace, without SSH or AI calls. */
class AgentTranscriptPresentationTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void longMixedTranscriptUsesOneScrollAndKeepsRolesOptionsCopyAndApproval() throws Exception {
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            String longAssistant = ("排查步骤：先确认连接，再检查进程和日志。\n".repeat(4))
                    + "完整结尾：这条结论必须能够滚动看到。";
            view.printUser("请检查这台服务器的部署状态，并给出可执行的修复步骤。");
            view.printAssistant(longAssistant);
            view.printQuestion("你希望我先检查哪一项？", List.of("检查磁盘和日志", "重启服务", "暂时跳过"));
            // 用户实际输入原文（编号），不是解析后的选项文本。
            view.printUser("2");
            view.printStatus("正在规划任务并核对远端上下文。");
            view.printTechnical(AgentConsoleView.Role.THINK, "分析可能原因：".repeat(60));
            view.printError("处理 AI 命令时出错：模拟的服务返回错误。");
            view.printCommandProposal("rm -rf /tmp/demo && echo done", "高风险", approved -> {}, command -> {});

            Scene scene = new Scene(view, 352, 650);
            scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/style.css").toExternalForm());
            view.applyCss();
            view.layout();

            VBox transcript = (VBox) field(view, "transcript");
            assertTrue(transcript.lookupAll(".text-area").isEmpty(),
                    "message bodies must not use nested TextArea controls");
            assertTrue(transcript.lookupAll(".scroll-pane").isEmpty(),
                    "the transcript must be the only vertical scroll surface");
            assertFalse(transcript.lookupAll(".agent-message").isEmpty());
            String all = collectText(transcript);
            assertTrue(all.contains("完整结尾：这条结论必须能够滚动看到。"));
            assertTrue(all.contains("模拟的服务返回错误"));

            Node question = transcript.lookup(".agent-message-question");
            assertNotNull(question);
            assertEquals(3, question.lookupAll(".agent-message-option").size());
            String questionText = collectText(question);
            for (String option : List.of("检查磁盘和日志", "重启服务", "暂时跳过")) {
                assertTrue(questionText.contains(option), "option missing from question block: " + option);
            }
            assertEquals(1, transcript.lookupAll(".agent-message-question").size(),
                    "question and options must stay in one block");
            Node thinking = transcript.lookup(".agent-message-think");
            assertNotNull(thinking);
            TitledPane details = (TitledPane) thinking.lookup(".agent-message-details");
            assertNotNull(details, "long THINK content should use the compact details control");
            assertFalse(details.isExpanded(), "long THINK should start collapsed but retain full content");
            assertTrue(transcript.lookupAll(".agent-copy-button").size()
                    >= transcript.lookupAll(".agent-message").size());

            Node approval = transcript.lookup(".ai-command-card");
            assertNotNull(approval);
            assertEquals(4, approval.lookupAll(".button").size());

            view.resize(280, 650);
            view.applyCss();
            view.layout();
            assertTrue(view.getWidth() <= 281, "narrow width was not applied: " + view.getWidth());
            for (Node block : transcript.lookupAll(".agent-message")) {
                assertTrue(block.getBoundsInParent().getMaxX() <= transcript.getWidth() + 1,
                        "message clipped horizontally at 280px: " + block.getBoundsInParent());
            }
            TextArea input = (TextArea) field(view, "input");
            assertTrue(input.localToScene(input.getBoundsInLocal()).getMaxX()
                    <= view.localToScene(view.getBoundsInLocal()).getMaxX() + 1,
                    "fixed input must remain inside the narrow workspace");

            view.resize(352, 650);
            view.applyCss();
            view.layout();
        });
    }

    /**
     * Writes a separate, compact preview from a synthetic sample. This does not exercise SSH,
     * an AI provider, a command approval, or any execution path.
     */
    @Test
    void writesCleanTranscriptPreview() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        AtomicReference<Scene> sceneRef = new AtomicReference<>();
        AtomicReference<ScrollPane> scrollRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            view.setTarget("Agent · 阿里云 · 固定任务目标");
            view.setStatus("演示预览 · 未执行");
            view.printUser("我想把 Docker 安装到 /docker。");
            view.printAssistant("系统检查完成，可用空间约33GB。以下方案会修改服务配置，请先确认。");
            view.printQuestion("请选择安装方式", List.of(
                    "官方仓库安装",
                    "发行版包管理器",
                    "静态二进制 + 数据目录 /docker（推荐）"));
            view.printUser("按第3种安装");
            view.printStatus("正在准备安装方案，尚未执行。");
            view.printTechnical(AgentConsoleView.Role.THINK,
                    "核对 systemd 版本、挂载点和 Docker 数据目录。".repeat(40));
            view.printTechnical(AgentConsoleView.Role.TOOL,
                    "工具输出（示意）：检查 /docker 挂载点、可用空间和 systemd 版本。\n".repeat(8));
            // Preview-only card: no decision handler is invoked and no command is executed.
            view.printCommandProposal("uname -a", "只读", approved -> {}, command -> {});

            Scene scene = new Scene(view, 352, 820);
            ThemeManager.getInstance().applyTheme(scene, ThemeManager.Theme.DARK);
            view.resize(352, 820);
            view.applyCss();
            view.layout();
            viewRef.set(view);
            sceneRef.set(scene);
            scrollRef.set((ScrollPane) field(view, "scroll"));
        });
        fx(() -> {});
        fx(() -> {
            AgentConsoleView view = viewRef.get();
            ScrollPane scroll = scrollRef.get();
            scroll.setVvalue(0);
            view.applyCss();
            view.layout();
            scroll.setVvalue(0);
            view.layout();
            assertEquals(0.0d, scroll.getVvalue(), 0.001d, "preview must start at the top");
            snapshotScene(sceneRef.get(), Path.of("target", "shots", "agent-transcript-clean.png"));
        });
    }

    @Test
    void scrolledUpTranscriptDoesNotFollowNewMessages() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        AtomicReference<ScrollPane> scrollRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            for (int i = 0; i < 12; i++) {
                view.printAssistant("第 " + i + " 条较长的检查记录：" + "内容".repeat(50));
            }
            Scene scene = new Scene(view, 352, 650);
            scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/style.css").toExternalForm());
            view.applyCss();
            view.layout();
            viewRef.set(view);
            scrollRef.set((ScrollPane) field(view, "scroll"));
        });
        fx(() -> {});
        fx(() -> {
            ScrollPane scroll = scrollRef.get();
            assertTrue(scroll.getContent().getBoundsInLocal().getHeight()
                    > scroll.getViewportBounds().getHeight());
            scroll.setVvalue(0);
        });
        fx(() -> viewRef.get().printStatus("用户向上查看时到达的新消息"));
        fx(() -> {});
        fx(() -> assertEquals(0.0d, scrollRef.get().getVvalue(), 0.001d,
                "new messages must not pull a user who scrolled up to the bottom"));
        fx(() -> scrollRef.get().setVvalue(1));
        fx(() -> viewRef.get().printStatus("用户在底部时到达的新消息"));
        fx(() -> {});
        fx(() -> assertEquals(1.0d, scrollRef.get().getVvalue(), 0.001d));
    }

    @Test
    void queuedFollowDoesNotOverrideAnImmediateUserScroll() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        AtomicReference<ScrollPane> scrollRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            for (int i = 0; i < 12; i++) {
                view.printAssistant("第 " + i + " 条较长的检查记录：" + "内容".repeat(50));
            }
            Scene scene = new Scene(view, 352, 650);
            scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/style.css").toExternalForm());
            view.applyCss();
            view.layout();
            viewRef.set(view);
            scrollRef.set((ScrollPane) field(view, "scroll"));
        });
        fx(() -> {});
        fx(() -> {
            ScrollPane scroll = scrollRef.get();
            scroll.setVvalue(1);
            viewRef.get().printStatus("排队中的新消息");
            // The user scrolls up before the queued follow callback runs.
            scroll.setVvalue(0);
        });
        fx(() -> {});
        fx(() -> assertEquals(0.0d, scrollRef.get().getVvalue(), 0.001d,
                "queued follow must not override a later user scroll"));
    }

    private static String collectText(Node node) {
        StringBuilder text = new StringBuilder();
        collectText(node, text);
        return text.toString();
    }

    private static void collectText(Node node, StringBuilder text) {
        if (node instanceof Text value) text.append(value.getText()).append('\n');
        else if (node instanceof Label label) text.append(label.getText()).append('\n');
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectText(child, text);
        }
    }

    private static void snapshotScene(Scene scene, Path target) throws Exception {
        WritableImage image = scene.snapshot(null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        Files.createDirectories(target.getParent());
        assertTrue(ImageIO.write(buffered, "png", target.toFile()), "Could not write preview: " + target);
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
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
}
