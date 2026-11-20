package com.makaohub.backend.storage.service;

import com.makaohub.backend.storage.config.SupabaseStorageProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AvatarStorageDeleteTest {
    @Test void removesTheExactAvatarUsingSupabaseBatchDelete() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/storage/v1/object/avatars", exchange -> {
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new SupabaseStorageClient(new SupabaseStorageProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "avatars", "docs", "listings"));
            client.deleteAvatar("user/photo.jpg");
            assertEquals("DELETE /storage/v1/object/avatars {\"prefixes\":[\"user/photo.jpg\"]}", request.get());
        } finally { server.stop(0); }
    }
}
