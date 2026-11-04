/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.runzhi.aishell.terminal.zmodem.ZModemInterceptor;
import com.runzhi.aishell.terminal.zmodem.ZModemStream;
import com.runzhi.aishell.terminal.zmodem.ZModemSupport;
import com.techsenger.jeditermfx.core.Questioner;
import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Bridges a connected JSch session to JediTermFX.
 * <p>
 * 终端字节按 UTF-8 增量解码后交给终端控件；解码前先经过 ZMODEM 拦截器，
 * 命中帧前导时由协议引擎接管字节流完成文件传输（对端 sz 则接收、rz 则发送）。
 */
public final class JschTtyConnector implements TtyConnector {
    private final String name;
    private final ChannelShell channel;
    private final OutputStream writer;
    private final ZModemStream raw;
    private final ZModemInterceptor zmodem;
    private final Consumer<String> directoryListener;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final byte[] rawBuffer = new byte[8192];
    private int rawLength;
    private int rawPosition;
    private final StringBuilder oscBuffer = new StringBuilder();
    private int escapeState;

    public JschTtyConnector(Session session, String name) throws JSchException, IOException {
        this(session, name, null, null);
    }

    public JschTtyConnector(Session session, String name, Consumer<String> directoryListener)
            throws JSchException, IOException {
        this(session, name, directoryListener, null);
    }

    public JschTtyConnector(Session session, String name, Consumer<String> directoryListener,
                            ZModemSupport zmodemSupport) throws JSchException, IOException {
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH session is not connected");
        }
        this.name = name;
        this.directoryListener = directoryListener;
        this.channel = (ChannelShell) session.openChannel("shell");
        channel.setPty(true);
        channel.setPtyType("xterm-256color", 120, 36, 0, 0);
        this.writer = channel.getOutputStream();
        this.raw = new ZModemStream(channel.getInputStream());
        this.zmodem = new ZModemInterceptor(raw, writer, zmodemSupport);
        channel.connect(15_000);
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        while (true) {
            if (rawPosition < rawLength) {
                ByteBuffer bytes = ByteBuffer.wrap(rawBuffer, rawPosition, rawLength - rawPosition);
                CharBuffer chars = CharBuffer.wrap(buffer, offset, length);
                CoderResult result = decoder.decode(bytes, chars, false);
                rawPosition = bytes.position();
                int produced = chars.position() - offset;
                if (produced > 0) {
                    if (directoryListener != null) {
                        inspectTerminalMetadata(buffer, offset, produced);
                    }
                    return produced;
                }
                if (result.isError()) {
                    continue;
                }
                // 缓冲里只剩不完整的多字节序列：补充更多字节
                int remaining = rawLength - rawPosition;
                if (remaining > 0) {
                    System.arraycopy(rawBuffer, rawPosition, rawBuffer, 0, remaining);
                }
                rawLength = remaining;
                rawPosition = 0;
            } else {
                rawLength = 0;
                rawPosition = 0;
            }

            while (rawLength < rawBuffer.length) {
                int b = raw.read();
                if (b < 0) {
                    if (rawLength == 0) {
                        return -1;
                    }
                    break;
                }
                if (zmodem.onRawByte(b)) {
                    // 本次 ZMODEM 会话已结束（字节被协议引擎消费），继续读终端数据
                    continue;
                }
                rawBuffer[rawLength++] = (byte) b;
                if (raw.available() == 0) {
                    break;   // 已有数据先交给终端，避免攒包
                }
            }
            if (rawLength == 0) {
                continue;
            }
        }
    }

    /** ZMODEM 传输进行中（界面可据此显示状态）。 */
    public ZModemInterceptor zmodemInterceptor() {
        return zmodem;
    }

    private void inspectTerminalMetadata(char[] buffer, int offset, int count) {
        for (int i = offset; i < offset + count; i++) {
            char ch = buffer[i];
            if (escapeState == 0) {
                if (ch == '\u001b') escapeState = 1;
            } else if (escapeState == 1) {
                if (ch == ']') {
                    oscBuffer.setLength(0);
                    escapeState = 2;
                } else {
                    escapeState = ch == '\u001b' ? 1 : 0;
                }
            } else if (escapeState == 2) {
                if (ch == '\u0007') {
                    publishDirectory(oscBuffer.toString());
                    escapeState = 0;
                } else if (ch == '\u001b') {
                    escapeState = 3;
                } else if (oscBuffer.length() < 4096) {
                    oscBuffer.append(ch);
                }
            } else {
                if (ch == '\\') publishDirectory(oscBuffer.toString());
                escapeState = ch == '\u001b' ? 1 : 0;
            }
        }
    }

    private void publishDirectory(String osc) {
        String directory = null;
        if (osc.startsWith("7;file://")) {
            String value = osc.substring("7;file://".length());
            int slash = value.indexOf('/');
            if (slash >= 0) directory = value.substring(slash);
        } else if (osc.startsWith("0;") || osc.startsWith("2;")) {
            String title = osc.substring(2);
            int separator = title.indexOf(':');
            if (title.indexOf('@') >= 0 && separator >= 0 && separator + 1 < title.length()) {
                directory = title.substring(separator + 1).trim();
            }
        }
        if (directory != null && !directory.isBlank()) directoryListener.accept(directory);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (zmodem.isTransferring()) {
            return;   // 传输期间的按键不能混入协议字节流
        }
        writer.write(bytes);
        writer.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected() && !channel.isClosed();
    }

    @Override
    public void resize(TermSize size) {
        if (size != null && isConnected()) {
            channel.setPtySize(Math.max(1, size.getColumns()), Math.max(1, size.getRows()), 0, 0);
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (isConnected()) {
            Thread.sleep(100);
        }
        return channel.getExitStatus();
    }

    @Override
    public boolean ready() throws IOException {
        return raw.available() > 0;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() {
        zmodem.cancel();
        channel.disconnect();
    }

    @Override
    public boolean init(Questioner questioner) {
        return true;
    }
}
