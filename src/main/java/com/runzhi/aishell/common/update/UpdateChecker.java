/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用更新检查器：查询 GitHub 最新 release并与当前版本比较。
 * <p>
 * 查询失败一律视为「无新版本」，绝不打断用户；版本比较按点分字段逐段进行，
 * 数字段按数值比较、非数字段按字典序比较（如 1.10.0 晚于 1.9.0）。
 */
@Slf4j
public final class UpdateChecker {

    /** GitHub releases/latest 接口；仓库与关于对话框里的 APP_GITHUB_URL 保持一致。 */
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/RunzhiH/ai-shell/releases/latest";

    /** 接口返回缺失 html_url 时的回退页面。 */
    private static final String FALLBACK_RELEASE_PAGE =
            "https://github.com/RunzhiH/ai-shell/releases/latest";

    /** 发版说明最长展示长度，超出截断。 */
    private static final int NOTES_LIMIT = 400;

    private UpdateChecker() {
        // 工具类，禁止实例化
    }

    /** 一个发行附件（安装包），供自更新下载。 */
    public record ReleaseAsset(String name, String url, long size, String digest) {
        public ReleaseAsset(String name, String url, long size) {
            this(name, url, size, null);
        }
    }

    /** 一次更新检查的结果。 */
    public record UpdateInfo(String latestVersion, String releaseUrl, String releaseNotes,
                             List<ReleaseAsset> assets) {
    }

    /**
     * 查询 GitHub 最新 release。
     *
     * @return 最新版本信息；网络错误、非 200 或解析失败时返回 Optional.empty()
     */
    public static Optional<UpdateInfo> fetchLatestRelease() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "AIShell-UpdateChecker")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("查询更新返回非 200 状态: {}", response.statusCode());
                return Optional.empty();
            }
            return parseReleaseJson(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("更新检查失败（视为无新版本）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 解析 releases/latest 响应；缺 tag_name 或解析失败时返回 Optional.empty()。 */
    static Optional<UpdateInfo> parseReleaseJson(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json == null || !json.has("tag_name")) {
                log.debug("更新响应缺少 tag_name 字段");
                return Optional.empty();
            }
            String tag = json.get("tag_name").getAsString();
            String url = json.has("html_url") && !json.get("html_url").isJsonNull()
                    ? json.get("html_url").getAsString() : FALLBACK_RELEASE_PAGE;
            String notes = json.has("body") && !json.get("body").isJsonNull()
                    ? json.get("body").getAsString() : "";
            List<ReleaseAsset> assets = new ArrayList<>();
            if (json.has("assets") && json.get("assets").isJsonArray()) {
                for (var element : json.getAsJsonArray("assets")) {
                    JsonObject item = element.getAsJsonObject();
                    String name = item.has("name") && !item.get("name").isJsonNull()
                            ? item.get("name").getAsString() : "";
                    String assetUrl = item.has("browser_download_url")
                            && !item.get("browser_download_url").isJsonNull()
                                    ? item.get("browser_download_url").getAsString() : "";
                    long size = item.has("size") && !item.get("size").isJsonNull()
                            ? item.get("size").getAsLong() : 0L;
                    String digest = item.has("digest") && !item.get("digest").isJsonNull()
                            ? item.get("digest").getAsString() : null;
                    if (!name.isBlank() && !assetUrl.isBlank()) {
                        assets.add(new ReleaseAsset(name, assetUrl, size, digest));
                    }
                }
            }
            return Optional.of(new UpdateInfo(normalizeVersion(tag), url, trimNotes(notes), List.copyOf(assets)));
        } catch (Exception e) {
            log.debug("解析更新响应失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 判断 latest 是否比 current 新。逐段比较点分版本号：都是数字按数值比较，
     * 否则按字典序比较（空段视为最小）。分段不足的版本按空段补齐，因此 1.2 晚于 1.2.0 之前的 1.1。
     *
     * @param current 当前版本，如 "1.2.0"
     * @param latest  最新版本，可带 v 前缀，如 "v1.10.0"
     * @return true 表示 latest 严格大于 current
     */
    public static boolean isNewer(String current, String latest) {
        String a = normalizeVersion(current);
        String b = normalizeVersion(latest);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        String[] currentParts = a.split("\\.");
        String[] latestParts = b.split("\\.");
        int count = Math.max(currentParts.length, latestParts.length);
        for (int i = 0; i < count; i++) {
            String x = i < currentParts.length ? currentParts[i] : "";
            String y = i < latestParts.length ? latestParts[i] : "";
            int cmp = compareParts(x, y);
            if (cmp != 0) {
                return cmp < 0;
            }
        }
        return false;
    }

    /** 去掉版本标签前的 v/V 前导与首尾空白；null 返回空串。 */
    public static String normalizeVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        while (trimmed.length() > 1 && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static int compareParts(String x, String y) {
        if (x.isEmpty() && y.isEmpty()) {
            return 0;
        }
        Integer xNum = parseNumeric(x);
        Integer yNum = parseNumeric(y);
        if (xNum != null && yNum != null) {
            return xNum.compareTo(yNum);
        }
        // 空段当作 0，与其他数字段按数值比较；其余情形退化为字典序
        if (xNum != null && y.isEmpty()) {
            return xNum == 0 ? 0 : 1;
        }
        if (yNum != null && x.isEmpty()) {
            return yNum == 0 ? 0 : -1;
        }
        return x.compareTo(y);
    }

    private static Integer parseNumeric(String part) {
        if (part == null || part.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimNotes(String notes) {
        if (notes == null) {
            return "";
        }
        String trimmed = notes.strip();
        if (trimmed.length() > NOTES_LIMIT) {
            return trimmed.substring(0, NOTES_LIMIT) + "…";
        }
        return trimmed;
    }
}
