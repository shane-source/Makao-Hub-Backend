package com.makaohub.backend.storage.service;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceBodyLimitTest {
    @Test void exactBoundedDownloadIsReturnedAndOversizeIsCancelled() {
        var body = new SupabaseStorageClient.LimitedEvidenceBody(3);
        var subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1,2}), ByteBuffer.wrap(new byte[] {3})));
        body.onComplete();
        assertArrayEquals(new byte[] {1,2,3}, body.getBody().toCompletableFuture().join());
        var excess = new SupabaseStorageClient.LimitedEvidenceBody(2);
        excess.onSubscribe(subscription);
        excess.onNext(List.of(ByteBuffer.wrap(new byte[] {1,2,3})));
        verify(subscription).cancel();
        assertTrue(excess.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
