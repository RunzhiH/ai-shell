/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 外部编辑器检测（方案 §3.3 第 3 步 / P2）：默认支持 VS Code、Notepad++、Sublime Text，
 * 以及 gVim、Windows 记事本、macOS TextEdit、Linux gedit/kate 等常用编辑器；
 * 都不可用时使用系统默认程序。自定义命令（含 {file}）优先级最高。
 *
 * <p>检测只读 PATH 与常见安装目录，不做任何注册表写入；命令以参数列表启动，不经过 shell。
 */
public final class ExternalEditorDetector {

    /** Windows 记事本（几乎所有 Windows 都可用，作为系统默认之前的兜底）。 */
    private static final String WINDOWS_NOTEPAD = "C:/Windows/notepad.exe";

    private ExternalEditorDetector() {
    }

    /**
     * 按优先级返回可用编辑器：自定义命令 > VS Code > Notepad++ > Sublime Text >
     * gVim > Notepad（Windows）/ TextEdit（macOS）/ gedit · kate（Linux）> 系统默认。
     */
    public static List<ExternalEditor> detectAvailable() {
        List<ExternalEditor> editors = new ArrayList<>();
        ExternalEditor custom = parseCustomCommand();
        if (custom != null) editors.add(custom);
        addIfFound(editors, "code", ExternalEditor.Source.VSCODE);
        addIfFound(editors, "code.cmd", ExternalEditor.Source.VSCODE);
        addIfFound(editors, "notepad++", ExternalEditor.Source.NOTEPAD_PLUS_PLUS);
        addIfFound(editors, "subl", ExternalEditor.Source.SUBLIME);
        addIfFound(editors, "sublime_text", ExternalEditor.Source.SUBLIME);
        addIfFound(editors, "gvim", ExternalEditor.Source.VIM);
        addIfFound(editors, "vim", ExternalEditor.Source.VIM);
        addIfFound(editors, "idea64", ExternalEditor.Source.JETBRAINS);
        addIfFound(editors, "idea", ExternalEditor.Source.JETBRAINS);
        addWindowsPaths(editors);
        addJetBrainsPaths(editors);
        addMacPaths(editors);
        addLinuxEditors(editors);
        if (editors.isEmpty()) {
            editors.add(systemDefault());
        }
        return editors;
    }

    /** 首选编辑器：已记住的选择 > 检测到的第一个 > 系统默认。 */
    public static ExternalEditor preferred() {
        List<ExternalEditor> editors = detectAvailable();
        if (editors.isEmpty()) return systemDefault();
        String remembered = EditorPreferences.preferredEditorName();
        if (remembered != null && !remembered.isBlank()) {
            for (ExternalEditor editor : editors) {
                if (remembered.equals(editor.name())) return editor;
            }
        }
        return editors.get(0);
    }

    /** 系统默认程序（不启动第三方编辑器，交给操作系统关联程序）。 */
    public static ExternalEditor systemDefault() {
        return new ExternalEditor("System default", List.of(), ExternalEditor.Source.SYSTEM_DEFAULT);
    }

    private static ExternalEditor parseCustomCommand() {
        String command = EditorPreferences.externalCommand();
        if (command == null || command.isBlank() || !command.contains("{file}")) return null;
        List<String> parts = tokenize(command);
        if (parts.isEmpty()) return null;
        return new ExternalEditor(customName(parts), parts, ExternalEditor.Source.CUSTOM);
    }

    private static String customName(List<String> parts) {
        String program = parts.get(0);
        int slash = Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\'));
        String name = slash < 0 ? program : program.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + " (custom)";
    }

    private static void addIfFound(List<ExternalEditor> editors, String launcher, ExternalEditor.Source source) {
        if (isAnyNamed(editors, source)) return;
        Path resolved = resolveInPath(launcher);
        if (resolved != null) {
            editors.add(new ExternalEditor(displayName(source), List.of(resolved.toString(), "{file}"), source));
        }
    }

    /** JetBrains 全家桶：Program FilesJetBrains<产品>inidea64.exe（任意产品目录）。 */
    private static void addJetBrainsPaths(List<ExternalEditor> editors) {
        if (!onWindows()) return;
        if (isAnyNamed(editors, ExternalEditor.Source.JETBRAINS)) return;
        for (String base : new String[]{env("ProgramFiles"), env("ProgramFiles(X86)")}) {
            if (base == null) continue;
            Path jetbrains = Path.of(base, "JetBrains");
            if (!Files.isDirectory(jetbrains)) continue;
            try {
                for (Path product : Files.list(jetbrains).toList()) {
                    Path bin = product.resolve("bin");
                    if (!Files.isDirectory(bin)) continue;
                    for (String executable : new String[]{"idea64.exe", "idea.exe", "webstorm64.exe",
                            "pycharm64.exe", "clion64.exe", "goland64.exe", "phpstorm64.exe", "rider64.exe"}) {
                        Path candidate = bin.resolve(executable);
                        if (Files.isExecutable(candidate)) {
                            found(editors, candidate, ExternalEditor.Source.JETBRAINS,
                                    product.getFileName().toString());
                            break;
                        }
                    }
                }
            } catch (java.io.IOException ignored) {
                // 单个目录列举失败不影响其它检测
            }
        }
    }

    private static void addWindowsPaths(List<ExternalEditor> editors) {
        if (!onWindows()) return;
        String localAppData = env("LOCALAPPDATA");
        String programFiles = env("ProgramFiles");
        String programFilesX86 = env("ProgramFiles(X86)");
        String userProfile = env("USERPROFILE");
        if (localAppData != null) {
            found(editors, Path.of(localAppData, "Programs", "Microsoft VS Code", "Code.exe"),
                    ExternalEditor.Source.VSCODE);
            found(editors, Path.of(localAppData, "Programs", "Microsoft VS Code", "bin", "code.cmd"),
                    ExternalEditor.Source.VSCODE);
            found(editors, Path.of(localAppData, "Programs", "Microsoft VS Code", "bin", "code.cmd"),
                    ExternalEditor.Source.VSCODE);
        }
        if (programFiles != null) {
            found(editors, Path.of(programFiles, "Microsoft VS Code", "Code.exe"), ExternalEditor.Source.VSCODE);
            found(editors, Path.of(programFiles, "Notepad++", "notepad++.exe"),
                    ExternalEditor.Source.NOTEPAD_PLUS_PLUS);
            found(editors, Path.of(programFiles, "Sublime Text", "sublime_text.exe"), ExternalEditor.Source.SUBLIME);
            found(editors, Path.of(programFiles, "Vim", "gvim.exe"), ExternalEditor.Source.VIM);
        }
        if (programFilesX86 != null) {
            found(editors, Path.of(programFilesX86, "Notepad++", "notepad++.exe"),
                    ExternalEditor.Source.NOTEPAD_PLUS_PLUS);
            found(editors, Path.of(programFilesX86, "Sublime Text", "sublime_text.exe"),
                    ExternalEditor.Source.SUBLIME);
            found(editors, Path.of(programFilesX86, "Microsoft VS Code", "Code.exe"), ExternalEditor.Source.VSCODE);
            found(editors, Path.of(programFilesX86, "Vim", "gvim.exe"), ExternalEditor.Source.VIM);
        }
        if (userProfile != null) {
            // Scoop 安装位置
            found(editors, Path.of(userProfile, "scoop", "apps", "notepadplusplus", "current", "notepad++.exe"),
                    ExternalEditor.Source.NOTEPAD_PLUS_PLUS);
            found(editors, Path.of(userProfile, "scoop", "apps", "sublime-text", "current", "sublime_text.exe"),
                    ExternalEditor.Source.SUBLIME);
            found(editors, Path.of(userProfile, "scoop", "apps", "vscodium", "current", "vscodium.exe"),
                    ExternalEditor.Source.VSCODE);
            found(editors, Path.of(userProfile, "scoop", "apps", "vim", "current", "gvim.exe"),
                    ExternalEditor.Source.VIM);
        }
        // Windows 记事本总可用：系统默认之前的最终兜底
        found(editors, Path.of(WINDOWS_NOTEPAD), ExternalEditor.Source.WINDOWS_NOTEPAD);
    }

    private static void addMacPaths(List<ExternalEditor> editors) {
        if (!onMac()) return;
        Path applications = Path.of("/Applications");
        found(editors, applications.resolve("Visual Studio Code.app/Contents/Resources/app/bin/code"),
                ExternalEditor.Source.VSCODE);
        found(editors, Path.of("/usr/local/bin/code"), ExternalEditor.Source.VSCODE);
        found(editors, applications.resolve("Sublime Text.app/Contents/SharedSupport/bin/subl"),
                ExternalEditor.Source.SUBLIME);
        found(editors, Path.of("/usr/local/bin/subl"), ExternalEditor.Source.SUBLIME);
        // TextEdit：open -a TextEdit（命令模板，无需 PATH）
        if (!isAnyNamed(editors, ExternalEditor.Source.TEXT_EDIT)) {
            editors.add(new ExternalEditor("TextEdit",
                    List.of("open", "-a", "TextEdit", "{file}"), ExternalEditor.Source.TEXT_EDIT));
        }
    }

    private static void addLinuxEditors(List<ExternalEditor> editors) {
        if (onWindows() || onMac()) return;
        addIfFound(editors, "notepadqq", ExternalEditor.Source.NOTEPAD_PLUS_PLUS);
        addIfFound(editors, "gedit", ExternalEditor.Source.GEDIT);
        addIfFound(editors, "kate", ExternalEditor.Source.KATE);
        addIfFound(editors, "xed", ExternalEditor.Source.GEDIT);
        addIfFound(editors, "mousepad", ExternalEditor.Source.GEDIT);
        addIfFound(editors, "pluma", ExternalEditor.Source.GEDIT);
    }

    private static void found(List<ExternalEditor> editors, Path exe, ExternalEditor.Source source) {
        found(editors, exe, source, displayName(source));
    }

    private static void found(List<ExternalEditor> editors, Path exe, ExternalEditor.Source source, String name) {
        if (isAnyNamed(editors, source)) return;
        if (Files.isExecutable(exe)) {
            editors.add(new ExternalEditor(name, List.of(exe.toString(), "{file}"), source));
        }
    }

    private static boolean isAnyNamed(List<ExternalEditor> editors, ExternalEditor.Source source) {
        return editors.stream().anyMatch(editor -> editor.source() == source);
    }

    private static String displayName(ExternalEditor.Source source) {
        return switch (source) {
            case VSCODE -> "Visual Studio Code";
            case NOTEPAD_PLUS_PLUS -> "Notepad++";
            case SUBLIME -> "Sublime Text";
            case VIM -> "Vim (gVim)";
            case JETBRAINS -> "IntelliJ IDEA";
            case WINDOWS_NOTEPAD -> "记事本 (Notepad)";
            case TEXT_EDIT -> "TextEdit";
            case GEDIT -> "gedit";
            case KATE -> "Kate";
            default -> "Editor";
        };
    }

    private static Path resolveInPath(String command) {
        return resolveIn(env("PATH"), command);
    }

    /** 按给定 PATH 环境解析命令（测试入口）；Windows 上按 PATHEXT 优先选可执行扩展名。 */
    static Path resolveIn(String pathEnv, String command) {
        if (pathEnv == null || pathEnv.isEmpty()) return null;
        // Windows 上可执行文件必须带扩展名：无扩展名的条目（如 VS Code bin/code 这种 shell 脚本）
        // 会被 CreateProcess 以错误 193 拒绝，因此只按 PATHEXT 顺序解析；命令本身带扩展名时不再追加。
        List<String> candidates = new ArrayList<>();
        boolean hasExtension = command.lastIndexOf('.') > Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        if (onWindows() && !hasExtension) {
            String pathext = env("PATHEXT");
            candidates = (pathext == null || pathext.isBlank())
                    ? List.of(".COM", ".EXE", ".BAT", ".CMD")
                    : java.util.Arrays.stream(pathext.split(File.pathSeparator))
                            .map(String::trim).filter(extension -> !extension.isEmpty()).toList();
        } else {
            candidates.add("");
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String extension : candidates) {
                Path candidate = Path.of(dir, command + extension);
                if (Files.isExecutable(candidate)) return candidate;
            }
        }
        return null;
    }

    /** 简单分词：按空白拆分，双引号内保留空格（不含转义处理，模板足够用）。 */
    static List<String> tokenize(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean onMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String env(String name) {
        return System.getenv(name);
    }
}
