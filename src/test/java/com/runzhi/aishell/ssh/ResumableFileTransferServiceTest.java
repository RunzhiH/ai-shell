package com.runzhi.aishell.ssh;

import com.jcraft.jsch.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 断点续传完成判定的单元测试。
 * <p>
 * 远程侧的块读取通过子类桩掉，本地侧读取真实临时文件，覆盖：
 * 大小相等且首尾一致 → 认可完成；大小相等但内容不同 → 判定未完成（触发重传）；
 * 远程块读取异常 → 判定未完成（保守重传）。
 */
class ResumableFileTransferServiceTest {

    private static final int SIZE = 100_000;

    /** 桩掉远程读取，按预设字节数组返回指定偏移的块。 */
    private static class FakeRemoteService extends ResumableFileTransferService {
        private byte[] remoteBytes;
        private boolean remoteBroken;

        FakeRemoteService() {
            super((Session) null);
        }

        void setRemoteBytes(byte[] remoteBytes) {
            this.remoteBytes = remoteBytes;
        }

        void setRemoteBroken() {
            this.remoteBroken = true;
        }

        @Override
        byte[] readRemoteChunk(String remoteFilePath, long offset, int len) {
            if (remoteBroken) throw new RuntimeException("simulated sftp failure");
            return Arrays.copyOfRange(remoteBytes, (int) offset, (int) offset + len);
        }
    }

    private byte[] localFile(Path dir, byte head, byte tail) throws Exception {
        Path file = dir.resolve("local.bin");
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.setLength(SIZE);
            out.seek(0);
            out.write(head);
            out.seek(SIZE - 1);
            out.write(tail);
        }
        byte[] all = new byte[SIZE];
        all[0] = head;
        all[SIZE - 1] = tail;
        return all;
    }

    @Test
    void equalSizeAndMatchingHeadTailCountsAsComplete(@TempDir Path dir) throws Exception {
        FakeRemoteService service = new FakeRemoteService();
        byte[] local = localFile(dir, (byte) 0x11, (byte) 0x22);
        service.setRemoteBytes(local);

        assertTrue(service.remoteChunksMatch(dir.resolve("local.bin").toString(), "remote://x", SIZE));
    }

    @Test
    void equalSizeButDifferentTailIsNotComplete(@TempDir Path dir) throws Exception {
        FakeRemoteService service = new FakeRemoteService();
        byte[] local = localFile(dir, (byte) 0x11, (byte) 0x22);
        byte[] remote = local.clone();
        remote[SIZE - 1] = 0x33; // 同名同大小，但结尾不同
        service.setRemoteBytes(remote);

        assertFalse(service.remoteChunksMatch(dir.resolve("local.bin").toString(), "remote://x", SIZE));
    }

    @Test
    void equalSizeButDifferentHeadIsNotComplete(@TempDir Path dir) throws Exception {
        FakeRemoteService service = new FakeRemoteService();
        byte[] local = localFile(dir, (byte) 0x11, (byte) 0x22);
        byte[] remote = local.clone();
        remote[0] = 0x33; // 开头不同
        service.setRemoteBytes(remote);

        assertFalse(service.remoteChunksMatch(dir.resolve("local.bin").toString(), "remote://x", SIZE));
    }

    @Test
    void remoteReadFailureFailsVerificationSoTransferRestarts(@TempDir Path dir) throws Exception {
        FakeRemoteService service = new FakeRemoteService();
        localFile(dir, (byte) 0x11, (byte) 0x22);
        service.setRemoteBroken();

        assertFalse(service.remoteChunksMatch(dir.resolve("local.bin").toString(), "remote://x", SIZE));
    }

    @Test
    void readLocalChunkReadsHeadAndTail(@TempDir Path dir) throws Exception {
        ResumableFileTransferService service = new ResumableFileTransferService((Session) null);
        Path file = dir.resolve("local.bin");
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.setLength(SIZE);
            out.seek(0);
            out.write(0xAB);
            out.seek(SIZE - 1);
            out.write(0xCD);
        }

        byte[] head = service.readLocalChunk(file.toString(), 0, 32);
        byte[] tail = service.readLocalChunk(file.toString(), SIZE - 32, 32);

        assertEquals(32, head.length);
        assertEquals(32, tail.length);
        assertEquals((byte) 0xAB, head[0]);
        assertEquals((byte) 0xCD, tail[31]);
    }
}
