/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.techsenger.jeditermfx.core.Questioner;
import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Bridges a connected JSch session to JediTermFX without interpreting terminal bytes. */
public final class JschTtyConnector implements TtyConnector {
    private final String name;
    private final ChannelShell channel;
    private final Reader reader;
    private final OutputStream writer;
    private final Consumer<String> directoryListener;
    private final StringBuilder oscBuffer = new StringBuilder();
    private int escapeState;

    public JschTtyConnector(Session session, String name) throws JSchException, IOException {
        this(session, name, null);
    }

    public JschTtyConnector(Session session, String name, Consumer<String> directoryListener)
            throws JSchException, IOException {
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH session is not connected");
        }
        this.name = name;
        this.directoryListener = directoryListener;
        this.channel = (ChannelShell) session.openChannel("shell");
        channel.setPty(true);
        channel.setPtyType("xterm-256color", 120, 36, 0, 0);
        this.reader = new InputStreamReader(channel.getInputStream(), StandardCharsets.UTF_8);
        this.writer = channel.getOutputStream();
        channel.connect(15_000);
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        int count = reader.read(buffer, offset, length);
        if (count > 0 && directoryListener != null) {
            inspectTerminalMetadata(buffer, offset, count);
        }
        return count;
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
        return reader.ready();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() {
        channel.disconnect();
    }

    @Override
    public boolean init(Questioner questioner) {
        return true;
    }
}
