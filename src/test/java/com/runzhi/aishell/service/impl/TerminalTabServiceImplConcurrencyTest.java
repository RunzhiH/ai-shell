package com.runzhi.aishell.service.impl;

import javafx.application.Platform;
import javafx.scene.control.Tab;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TerminalTabServiceImpl 的并发安全回归测试。
 *
 * <p>tabToOutput/tabToInput 映射曾使用普通 HashMap，被 FX 线程（创建/关闭标签页）
 * 与后台 SSH 读取线程（写入输出、清理）并发访问。本测试验证它们现在是并发映射，
 * 且在多线程并发修改下不抛 ConcurrentModificationException。</p>
 *
 * <p>注意：不构造 InlineCssTextArea / TextField 等富文本控件——它们在无场景图的
 * headless 测试环境里初始化会抛 ArrayIndexOutOfBoundsException。改为只验证
 * 映射本身的并发语义。</p>
 */
class TerminalTabServiceImplConcurrencyTest {

    private static TerminalTabServiceImpl service;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");

        // 在 FX 线程构造服务实例
        CountDownLatch created = new CountDownLatch(1);
        AtomicReference<TerminalTabServiceImpl> ref = new AtomicReference<>();
        Platform.runLater(() -> {
            ref.set(new TerminalTabServiceImpl());
            created.countDown();
        });
        assertTrue(created.await(10, TimeUnit.SECONDS));
        service = ref.get();
    }

    @Test
    @DisplayName("getTabToOutput 返回的映射是并发映射（迭代时修改不抛 CME）")
    void outputMapIsConcurrent() {
        Map<Tab, javafx.scene.control.Tab> probeMap = new java.util.concurrent.ConcurrentHashMap<>();
        Tab tab = new Tab("probe");
        probeMap.put(tab, tab);
        // ConcurrentHashMap 的关键特征：允许在迭代时并发修改而不抛 CME
        assertDoesNotThrow(() -> {
            probeMap.entrySet().iterator().next();
            probeMap.remove(tab);
            probeMap.entrySet().iterator();
        }, "并发映射在迭代与修改同时发生时不应抛 ConcurrentModificationException");

        // 验证服务返回的确实是同一类并发映射
        assertNotNull(service.getTabToOutput());
        assertTrue(service.getTabToOutput() instanceof java.util.concurrent.ConcurrentHashMap,
                "tabToOutput 必须是 ConcurrentHashMap 以支持跨线程访问");
    }

    @Test
    @DisplayName("getTabToInput 返回的映射是并发映射")
    void inputMapIsConcurrent() {
        assertNotNull(service.getTabToInput());
        assertTrue(service.getTabToInput() instanceof java.util.concurrent.ConcurrentHashMap,
                "tabToInput 必须是 ConcurrentHashMap 以支持跨线程访问");
    }

    @Test
    @DisplayName("并发映射支持从非 FX 线程直接写入（后台 SSH 读取线程的访问模式）")
    void concurrentMapAcceptsWritesFromNonFxThread() throws Exception {
        Map<Tab, javafx.scene.control.Tab> map = new java.util.concurrent.ConcurrentHashMap<>();
        int threadCount = 8;
        int perThread = 25;
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int base = t * perThread;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        map.put(new Tab("tab-" + (base + i)), new Tab("tab-" + (base + i)));
                    }
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发写入超时");

        // HashMap 在多线程同时扩容时可能丢条目或抛 CME；ConcurrentHashMap 保证全部可见
        assertEquals(threadCount * perThread, map.size(), "并发写入的条目应全部可见");

        // 迭代清空后的映射不应抛异常
        assertDoesNotThrow(() -> {
            map.keySet().removeIf(tab -> tab.getText() != null && tab.getText().startsWith("tab-"));
            for (Map.Entry<Tab, javafx.scene.control.Tab> ignored : map.entrySet()) {
                // 仅迭代，验证稳定性
            }
        });
    }

    @Test
    @DisplayName("getTabId 在标签页无显式 id 时回退到标签文本")
    void getTabIdFallsBackToTabText() throws Exception {
        AtomicReference<String> ref = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            ref.set(service.getTabId(new Tab("session-A")));
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("session-A", ref.get(), "无显式 id 时应回退到标签文本");
    }

    @Test
    @DisplayName("getTabId 对 null 输入返回 null")
    void getTabIdReturnsNullForNullInput() {
        assertNull(service.getTabId(null), "null 输入应返回 null");
    }

    @Test
    @DisplayName("clearTerminalOutput 对未注册的标签页不抛异常")
    void clearTerminalOutputToleratesUnknownTab() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                service.clearTerminalOutput(new Tab("unknown"));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get(), "清理未注册标签页的输出不应抛异常");
    }
}
