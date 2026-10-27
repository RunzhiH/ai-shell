/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import com.google.gson.JsonObject;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.logging.LoggerManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP客户端工具类，提供通用的HTTP请求功能
 */
public class HttpClientUtil {
    private static final Logger logger = LoggerManager.getLogger(HttpClientUtil.class);
    private static final HttpClient sharedClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(AppConstants.HTTP_CLIENT_CONNECT_TIMEOUT_SECONDS))
            .build();

    /**
     * 发送POST请求
     *
     * @param url 请求URL
     * @param requestBody 请求体JSON对象
     * @param headers 请求头
     * @return 响应字符串，如果请求失败则返回错误信息
     */
    public static String doPost(String url, JsonObject requestBody, Map<String, String> headers) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(AppConstants.HTTP_REQUEST_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()));

            // 添加请求头
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = sharedClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body();
            } else {
                logger.error("HTTP request failed with status {}: {}", 
                        response.statusCode(), response.body());
                return "Error: API request failed with status " + response.statusCode();
            }
        } catch (java.net.http.HttpConnectTimeoutException e) {
            logger.error("HTTP connect timeout", e);
            return "Error: HTTP connect timeout. Please check your network connection and try again.";
        } catch (java.net.http.HttpTimeoutException e) {
            logger.error("HTTP request timeout", e);
            return "Error: HTTP request timeout. The server took too long to respond.";
        } catch (ConnectException e) {
            logger.error("Connection failed", e);
            return "Error: Connection failed. Please check the server address and network connection.";
        } catch (SocketTimeoutException e) {
            logger.error("Socket timeout", e);
            return "Error: Socket timeout. The server did not respond in time.";
        } catch (IOException e) {
            logger.error("HTTP request failed due to IO error", e);
            return "Error: Network error occurred - " + e.getMessage();
        } catch (InterruptedException e) {
            logger.error("HTTP request was interrupted", e);
            Thread.currentThread().interrupt(); // 重要：恢复中断状态
            return "Error: Request was interrupted - " + e.getMessage();
        }
    }

    /**
     * 发送GET请求
     *
     * @param url 请求URL
     * @param headers 请求头
     * @return 响应字符串，如果请求失败则返回错误信息
     */
    public static String doGet(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(AppConstants.HTTP_REQUEST_TIMEOUT_SECONDS))
                    .GET();

            // 添加请求头
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = sharedClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body();
            } else {
                logger.error("HTTP request failed with status {}: {}", 
                        response.statusCode(), response.body());
                return "Error: API request failed with status " + response.statusCode();
            }
        } catch (java.net.http.HttpConnectTimeoutException e) {
            logger.error("HTTP connect timeout", e);
            return "Error: HTTP connect timeout. Please check your network connection and try again.";
        } catch (java.net.http.HttpTimeoutException e) {
            logger.error("HTTP request timeout", e);
            return "Error: HTTP request timeout. The server took too long to respond.";
        } catch (ConnectException e) {
            logger.error("Connection failed", e);
            return "Error: Connection failed. Please check the server address and network connection.";
        } catch (SocketTimeoutException e) {
            logger.error("Socket timeout", e);
            return "Error: Socket timeout. The server did not respond in time.";
        } catch (IOException e) {
            logger.error("HTTP request failed due to IO error", e);
            return "Error: Network error occurred - " + e.getMessage();
        } catch (InterruptedException e) {
            logger.error("HTTP request was interrupted", e);
            Thread.currentThread().interrupt(); // 重要：恢复中断状态
            return "Error: Request was interrupted - " + e.getMessage();
        }
    }
}