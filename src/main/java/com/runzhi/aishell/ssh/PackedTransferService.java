/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 打包传输：本地目录压成一个 zip 上传，再让远端解压。
 * <p>
 * 小文件很多时，逐个 SFTP 上传的往返开销远大于带宽，打包后一次传完再解压明显更快。
 * 解压命令按常见环境依次尝试：unzip → busybox unzip → python3。
 */
public final class PackedTransferService {

    /** 远端解压成功的标记（echo 出来后在输出里查找）。 */
    public static final String UNPACK_MARKER = "__AISHELL_UNPACK_OK__";

    /**
     * 把目录压成临时 zip（保留目录内相对结构）。
     *
     * @param directory 要打包的目录
     * @param progress  已写入字节数回调，可为 null
     * @return 临时 zip 文件路径（调用方负责删除）
     */
    public Path zipDirectory(Path directory, Consumer<Long> progress) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("not a directory: " + directory);
        }
        Path zipFile = Files.createTempFile("aishell-pack-", ".zip");
        try (OutputStream fileOut = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            long[] written = {0};
            Path root = directory;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(root)) {
                        String name = relativeEntryName(root, dir) + "/";
                        zip.putNextEntry(new ZipEntry(name));
                        zip.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    zip.putNextEntry(new ZipEntry(relativeEntryName(root, file)));
                    byte[] buffer = new byte[64 * 1024];
                    try (var in = Files.newInputStream(file)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            zip.write(buffer, 0, n);
                            written[0] += n;
                            if (progress != null) {
                                progress.accept(written[0]);
                            }
                        }
                    }
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
            return zipFile;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(zipFile);
            throw e;
        }
    }

    private static String relativeEntryName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * 远端解压命令候选列表：每条命令成功后 echo 标记，失败则静默。
     * 命令按顺序尝试，任一成功即停止。
     */
    public static List<String> extractionCommands(String remoteArchive, String targetDir) {
        String archive = shellQuote(remoteArchive);
        String target = shellQuote(targetDir);
        String marker = "&& echo " + UNPACK_MARKER;
        List<String> commands = new ArrayList<>();
        commands.add("mkdir -p " + target + " && unzip -o -q " + archive + " -d " + target + " " + marker);
        commands.add("mkdir -p " + target + " && busybox unzip -o " + archive + " -d " + target
                + " >/dev/null 2>&1 " + marker);
        commands.add("mkdir -p " + target + " && python3 -m zipfile -e " + archive + " " + target + " "
                + marker);
        commands.add("mkdir -p " + target + " && python -m zipfile -e " + archive + " " + target + " "
                + marker);
        return commands;
    }

    /**
     * 逐个尝试解压命令，返回是否至少有一条成功。
     *
     * @param commandRunner 执行远端命令并返回输出的函数（如 SSHConnectionManager::executeCommand）
     */
    public boolean extractRemotely(Function<String, String> commandRunner,
                                   String remoteArchive, String targetDir) {
        for (String command : extractionCommands(remoteArchive, targetDir)) {
            try {
                String output = commandRunner.apply(command);
                if (output != null && output.contains(UNPACK_MARKER)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // 该命令不可用或执行失败，尝试下一条
            }
        }
        return false;
    }

    /** 删除远端临时压缩包（失败不影响传输结果）。 */
    public void removeRemoteArchive(Function<String, String> commandRunner, String remoteArchive) {
        try {
            commandRunner.apply("rm -f " + shellQuote(remoteArchive));
        } catch (RuntimeException ignored) {
            // 清理失败无需上报
        }
    }

    /** 单引号包裹并转义，避免路径中的空格与引号破坏命令。 */
    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
