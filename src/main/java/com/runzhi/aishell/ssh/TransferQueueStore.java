/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crash-safe metadata store for user-approved SFTP transfer jobs. Contains no credentials.
 * <p>
 * 同一队列文件上的全部句柄共享同一个规范实例（2026-10-23 审计 P0-7）：
 * 终端面板按 Tab 各持一个 Store，若各自维护独立内存列表与实例锁，
 * 一个 Tab 写回后另一个 Tab 的旧数据会把队列整表覆盖回去，已完成任务被"复活"。
 */
@lombok.extern.slf4j.Slf4j
public final class TransferQueueStore {
    public enum Direction { UPLOAD, DOWNLOAD }
    public enum Status { PENDING, RUNNING, INTERRUPTED, FAILED, CANCELLED }
    public record Job(String id, Direction direction, String host, String username, int port,
                      List<String> sources, String destination, String policy,
                      Status status, String updatedAt) {
        /** Legacy metadata has unknown port; it must not match a verified endpoint. */
        public Job(String id, Direction direction, String host, String username,
                   List<String> sources, String destination, String policy, Status status, String updatedAt) {
            this(id, direction, host, username, 0, sources, destination, policy, status, updatedAt);
        }
    }

    private static final Map<Path, TransferQueueStore> INSTANCES = new HashMap<>();
    private static final Object REGISTRY_LOCK = new Object();

    private final TransferQueueStore delegate;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Job> jobs;
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<String,String> progress=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,Runnable> cancelHandlers=new java.util.concurrent.ConcurrentHashMap<>();

    /** 仅供测试：清空共享实例注册表，模拟进程重启后的重新加载。 */
    static void resetSharedInstancesForTest() {
        synchronized (REGISTRY_LOCK) {
            INSTANCES.clear();
        }
    }

    public TransferQueueStore() {
        this(Path.of(System.getProperty("user.home"), ".aishell", "transfer-queue.json"));
    }

    public TransferQueueStore(Path file) {
        Path key = file.toAbsolutePath().normalize();
        synchronized (REGISTRY_LOCK) {
            TransferQueueStore canonical = INSTANCES.get(key);
            if (canonical != null) {
                this.delegate = canonical;
                this.file = null;
                this.jobs = null;
            } else {
                this.delegate = this;
                this.file = key;
                this.jobs = read();
                boolean changed = false;
                for (int i = 0; i < jobs.size(); i++) {
                    Job job = jobs.get(i);
                    if (job.status() == Status.RUNNING) {
                        jobs.set(i, withStatus(job, Status.INTERRUPTED));
                        changed = true;
                    }
                }
                if (changed) write();
                INSTANCES.put(key, this);
            }
        }
    }

    public Job enqueue(Direction direction, String host, String username,
                       List<String> sources, String destination, String policy) {
        return delegate.enqueue0(direction, host, username, 0, sources, destination, policy);
    }

    /** Persist the remote endpoint's explicit port; zero means legacy/unknown identity. */
    public Job enqueue(Direction direction, String host, int port, String username,
                       List<String> sources, String destination, String policy) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid transfer endpoint port");
        return delegate.enqueue0(direction, host, username, port, sources, destination, policy);
    }

    /** Recover only an exact endpoint; never guess a port for legacy records. */
    public List<Job> recoverable(String host, int port, String username) {
        return port < 1 ? List.of() : delegate.recoverable0(host, username).stream()
                .filter(job -> job.port() == port).toList();
    }

    public void markRunning(String id) { delegate.update0(id, Status.RUNNING); }
    public void markInterrupted(String id) { delegate.update0(id, Status.INTERRUPTED); }

    public void complete(String id) { delegate.complete0(id); }

    public List<Job> recoverable(String host, String username) { return delegate.recoverable0(host, username); }

    /** Snapshot metadata only; callers may display all sessions without borrowing their credentials. */
    public List<Job> snapshot() { return delegate.snapshot0(); }
    private synchronized List<Job> snapshot0() { return List.copyOf(jobs); }
    public AutoCloseable observe(Runnable listener) { delegate.listeners.add(listener); return ()->delegate.listeners.remove(listener); }
    public void markPending(String id) { delegate.update0(id,Status.PENDING); }
    public void markFailed(String id) { delegate.update0(id,Status.FAILED); }
    public void markCancelled(String id) { delegate.update0(id,Status.CANCELLED); }
    public void registerCancel(String id,Runnable cancel) { delegate.cancelHandlers.put(id,cancel); }
    public void clearCancel(String id) { delegate.cancelHandlers.remove(id); }
    public boolean canCancel(String id) { return delegate.cancelHandlers.containsKey(id); }
    /** Running jobs route cancellation to their owner; pending jobs are skipped by the worker. */
    public void cancel(String id) { Runnable cancel=delegate.cancel0(id); if(cancel!=null) cancel.run(); }
    private synchronized Runnable cancel0(String id) {
        Job job=jobs.stream().filter(item->item.id().equals(id)).findFirst().orElse(null);
        if(job==null) return null;
        if(job.status()==Status.PENDING) update0(id,Status.CANCELLED);
        return job.status()==Status.PENDING || job.status()==Status.RUNNING ? cancelHandlers.get(id) : null;
    }
    /** Atomic claim prevents a queued cancellation from being overwritten by a worker start. */
    public boolean startJob(String id) { return delegate.start0(id); }
    private synchronized boolean start0(String id) {
        Job job=jobs.stream().filter(item->item.id().equals(id)).findFirst().orElse(null);
        if(job==null || job.status()!=Status.PENDING) return false;
        update0(id,Status.RUNNING); return true;
    }
    public String progress(String id) { return delegate.progress.getOrDefault(id,"—"); }
    public void progress(String id,String value) { delegate.progress.put(id,value); delegate.notifyListeners(); }
    private void notifyListeners() {
        for(Runnable listener:listeners) try { listener.run(); } catch(RuntimeException failure) { log.warn("Queue observer failed",failure); }
    }
    public void removeFailed(String id) {
        Job job=snapshot().stream().filter(item->item.id().equals(id)).findFirst().orElse(null);
        if(job!=null && job.status()!=Status.RUNNING && job.status()!=Status.PENDING) complete(id);
    }

    private synchronized Job enqueue0(Direction direction, String host, String username, int port,
                                      List<String> sources, String destination, String policy) {
        Job job = new Job(UUID.randomUUID().toString(), direction, safe(host), safe(username), port,
                List.copyOf(sources), destination, policy, Status.PENDING, Instant.now().toString());
        jobs.add(job);
        write();
        return job;
    }

    private synchronized void update0(String id, Status status) {
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id().equals(id)) jobs.set(i, withStatus(jobs.get(i), status));
        }
        write();
    }

    private synchronized void complete0(String id) {
        jobs.removeIf(job -> job.id().equals(id));
        cancelHandlers.remove(id);
        progress.remove(id);
        write();
    }

    private synchronized List<Job> recoverable0(String host, String username) {
        return jobs.stream().filter(job -> job.host().equals(safe(host))
                && job.username().equals(safe(username))).toList();
    }

    private Job withStatus(Job job, Status status) {
        return new Job(job.id(), job.direction(), job.host(), job.username(), job.port(), job.sources(),
                job.destination(), job.policy(), status, Instant.now().toString());
    }

    private List<Job> read() {
        if (!Files.isRegularFile(file)) return new ArrayList<>();
        try {
            List<Job> loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<List<Job>>() {}.getType());
            return loaded == null ? new ArrayList<>() : new ArrayList<>(loaded);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(jobs), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new IllegalStateException("无法保存传输队列: " + file, e);
        }
        notifyListeners();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
