package com.makaohub.backend.verification.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Local INSTREAM only: no shell, document filename, remote plaintext socket or scan bypass. */
@Service
public class ClamAvScanner {
    private final String socketPath;
    private final long timeoutMillis;
    private final Semaphore capacity = new Semaphore(4);

    @Autowired
    public ClamAvScanner(@Value("${security.verification.clamav-socket-path:}") String socketPath) {
        this(socketPath, 20_000);
    }

    ClamAvScanner(String socketPath, long timeoutMillis) {
        this.socketPath = socketPath;
        this.timeoutMillis = timeoutMillis;
    }

    public void assertClean(byte[] bytes) {
        if (socketPath == null || socketPath.isBlank() || bytes == null
                || bytes.length == 0 || bytes.length > 10_485_760) {
            throw unavailable();
        }
        final Path localSocket;
        try {
            localSocket = Path.of(socketPath);
        } catch (InvalidPathException failure) {
            throw unavailable();
        }
        if (!localSocket.isAbsolute() || !capacity.tryAcquire()) throw unavailable();
        // Closing the channel before the executor unblocks timed-out I/O before joining its worker.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            Future<String> scan = executor.submit(() -> {
                channel.connect(UnixDomainSocketAddress.of(localSocket));
                writeAll(channel, ByteBuffer.wrap("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII)));
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    int count = Math.min(8192, bytes.length - offset);
                    writeAll(channel, ByteBuffer.allocate(4).putInt(count).flip());
                    writeAll(channel, ByteBuffer.wrap(bytes, offset, count));
                }
                writeAll(channel, ByteBuffer.allocate(4));
                var reply = new ByteArrayOutputStream();
                var next = ByteBuffer.allocate(1);
                while (reply.size() < 4096) {
                    next.clear();
                    if (channel.read(next) < 0) throw new IOException("Incomplete scanner reply");
                    next.flip();
                    if (!next.hasRemaining()) continue;
                    byte value = next.get();
                    if (value == 0) return reply.toString(StandardCharsets.US_ASCII);
                    reply.write(value);
                }
                throw new IOException("Scanner reply exceeded limit");
            });
            String reply;
            try { reply = scan.get(timeoutMillis, TimeUnit.MILLISECONDS); }
            catch (TimeoutException | InterruptedException failure) {
                scan.cancel(true);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw unavailable();
            }
            if ("stream: OK".equals(reply)) return;
            if (reply.startsWith("stream: ") && reply.endsWith(" FOUND")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The document did not pass its security scan.");
            }
            throw unavailable(); // ERROR, limits, unknown/malformed replies are never clean.
        } catch (IOException | ExecutionException failure) {
            throw unavailable();
        } finally { capacity.release(); }
    }

    private static void writeAll(SocketChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Document security scanning is unavailable. The file has not been confirmed.");
    }
}
