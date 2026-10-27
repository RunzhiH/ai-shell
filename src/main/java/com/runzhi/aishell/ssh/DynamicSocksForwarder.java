/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal SOCKS5 CONNECT proxy transported through SSH direct-tcpip channels. */
final class DynamicSocksForwarder implements AutoCloseable {
    private final Session session;
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ssh-socks-forwarder");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<ChannelDirectTCPIP> channels = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    DynamicSocksForwarder(Session session, String bindAddress, int port) throws IOException {
        this.session = session;
        this.server = new ServerSocket();
        this.server.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
        workers.execute(this::acceptLoop);
    }

    int port() { return server.getLocalPort(); }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                workers.execute(() -> handle(client));
            }
            // accept 的瞬态异常不应永久杀死转发器；只有主动 close 才退出（2026-10-23 审计 P1-26）
            catch (IOException e) { if (!running) break; }
        }
    }

    private void handle(Socket client) {
        ChannelDirectTCPIP channel = null;
        OutputStream output = null; // 提到 try 之外，catch 中才能回 SOCKS 失败应答（2026-10-23 审计 P1-26）
        try (client) {
            client.setTcpNoDelay(true);
            InputStream input = new BufferedInputStream(client.getInputStream());
            output = new BufferedOutputStream(client.getOutputStream());
            if (readByte(input) != 5) throw new IOException("仅支持 SOCKS5");
            int methods = readByte(input);
            byte[] offered = input.readNBytes(methods);
            boolean noAuthOffered = false;
            for (byte b : offered) if (b == 0) { noAuthOffered = true; break; }
            // RFC1928：客户端不提供 0x00（无认证）时应回 0xFF 并关闭（此前固定回 {5,0}）（2026-10-23 审计 P1-26）
            if (!noAuthOffered) {
                output.write(new byte[]{5, (byte) 0xFF});
                output.flush();
                return;
            }
            output.write(new byte[]{5, 0});
            output.flush();

            if (readByte(input) != 5 || readByte(input) != 1 || readByte(input) != 0) {
                sendFailure(output, 7);
                return;
            }
            int addressType = readByte(input);
            String host = switch (addressType) {
                case 1 -> inetAddress(input, 4);
                case 3 -> new String(readFixed(input, readByte(input)), java.nio.charset.StandardCharsets.UTF_8);
                case 4 -> inetAddress(input, 16);
                default -> {
                    sendFailure(output, 8); // 8 = 不支持的地址类型
                    throw new IOException("不支持的 SOCKS 地址类型");
                }
            };
            int targetPort = (readByte(input) << 8) | readByte(input);
            channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            channel.setHost(host);
            channel.setPort(targetPort);
            channel.setOrgIPAddress(client.getInetAddress().getHostAddress());
            channel.setOrgPort(client.getPort());
            InputStream remoteInput = channel.getInputStream();
            OutputStream remoteOutput = channel.getOutputStream();
            channels.add(channel);
            channel.connect(15_000);
            output.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            output.flush();
            ChannelDirectTCPIP active = channel;
            OutputStream clientOut = output;
            workers.execute(() -> copy(remoteInput, clientOut, active));
            copy(input, remoteOutput, active);
        } catch (IOException | JSchException e) {
            // 握手或建连失败必须回一个 SOCKS 失败应答，否则客户端只能等到自身超时（2026-10-23 审计 P1-26）
            try { if (output != null) sendFailure(output, 1); } catch (Exception ignored) { }
            if (channel != null) channel.disconnect();
        } finally {
            if (channel != null) {
                channels.remove(channel);
                channel.disconnect();
            }
        }
    }

    /** 读取恰好 length 字节，短读（客户端半开）时抛出而非用残缺数据建连。 */
    private static byte[] readFixed(InputStream input, int length) throws IOException {
        byte[] buffer = input.readNBytes(length);
        if (buffer.length != length) throw new EOFException("SOCKS 请求被截断");
        return buffer;
    }

    private static String inetAddress(InputStream input, int length) throws IOException {
        return InetAddress.getByAddress(readFixed(input, length)).getHostAddress();
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static void sendFailure(OutputStream output, int code) throws IOException {
        output.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    private void copy(InputStream input, OutputStream output, ChannelDirectTCPIP channel) {
        try (output) { input.transferTo(output); }
        catch (IOException ignored) { }
        finally { channel.disconnect(); }
    }

    @Override public void close() {
        running = false;
        try { server.close(); } catch (IOException ignored) { }
        channels.forEach(ChannelDirectTCPIP::disconnect);
        channels.clear();
        workers.shutdownNow();
    }
}
