package com.makaohub.backend.verification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol fixtures only, not a real antivirus or a claim of clean customer files. */
class ClamAvScannerTest {
    @TempDir Path directory;

    @Test void missingScannerFailsClosed() {
        var failure = assertThrows(ResponseStatusException.class,
                () -> new ClamAvScanner("").assertClean(new byte[] {1}));
        assertEquals(503, failure.getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> new ClamAvScanner("relative/socket").assertClean(new byte[] {1})).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> new ClamAvScanner("/invalid\u0000socket").assertClean(new byte[] {1})).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> new ClamAvScanner("/tmp/scanner").assertClean(null)).getStatusCode().value());
    }

    @Test void instreamFramesEveryByteAndAcceptsOnlyExactCleanReply() throws Exception {
        checkReply("stream: OK\0", 0, false);
    }

    @Test void malwareErrorAndUnknownRepliesCannotPass() throws Exception {
        checkReply("stream: Synthetic-Signature FOUND\0", 400, false);
        checkReply("stream: scanner limit ERROR\0", 503, false);
        checkReply("unexpected OK\0", 503, false);
    }

    @Test void incompleteOrTimedOutRepliesFailClosed() throws Exception {
        checkReply("stream: OK", 503, false);
        checkReply("", 503, true);
    }

    private void checkReply(String reply, int expectedStatus, boolean stall) throws Exception {
        Path socket = directory.resolve("scan-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        byte[] data = new byte[20_001];
        java.util.Arrays.fill(data, (byte) 42);
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            server.bind(UnixDomainSocketAddress.of(socket));
            var daemon = worker.submit(() -> {
                try (var connection = server.accept()) {
                    var input = new DataInputStream(Channels.newInputStream(connection));
                    assertArrayEquals("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII), input.readNBytes(10));
                    var received = new java.io.ByteArrayOutputStream();
                    while (true) {
                        int count = input.readInt();
                        if (count == 0) break;
                        assertTrue(count > 0 && count <= 8192);
                        received.writeBytes(input.readNBytes(count));
                    }
                    assertArrayEquals(data, received.toByteArray());
                    if (stall) { Thread.sleep(300); return null; }
                    Channels.newOutputStream(connection).write(reply.getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            var scanner = new ClamAvScanner(socket.toString(), stall ? 100 : 2000);
            if (expectedStatus == 0) assertDoesNotThrow(() -> scanner.assertClean(data));
            else assertEquals(expectedStatus, assertThrows(ResponseStatusException.class,
                    () -> scanner.assertClean(data)).getStatusCode().value());
            daemon.get(3, TimeUnit.SECONDS);
        }
    }
}
