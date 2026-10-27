package com.runzhi.aishell.ai;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIStreamHttpClientTest {
    @Test
    void readsSseAndJsonLinesAsSeparateDeltas() throws Exception {
        assertEquals(List.of("a", "b"), runStream(
                "data: {\"text\":\"a\"}\n\ndata: {\"text\":\"b\"}\n\ndata: [DONE]\n\n", true));
        assertEquals(List.of("a", "b"), runStream(
                "{\"text\":\"a\"}\n{\"text\":\"b\"}\n", false));
    }

    private List<String> runStream(String responseBody, boolean sse) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> serverTask = executor.submit(() -> serveOnce(server, responseBody,
                    sse ? "text/event-stream" : "application/x-ndjson"));
            HttpRequest request = HttpRequest.newBuilder(new URI("http://127.0.0.1:" + server.getLocalPort()))
                    .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
            List<String> deltas = new ArrayList<>();
            AIStreamHttpClient.stream(HttpClient.newHttpClient(), request, sse,
                    json -> json.get("text").getAsString(), deltas::add, () -> false);
            serverTask.get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
            return deltas;
        }
    }

    private static void serveOnce(ServerSocket server, String body, String contentType) {
        try (Socket socket = server.accept()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:"))
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
            for (int i = 0; i < contentLength; i++) reader.read();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType
                    + "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
