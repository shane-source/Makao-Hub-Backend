package com.makaohub.backend.storage.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.makaohub.backend.storage.config.SupabaseStorageProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.util.Locale;
import java.util.Map;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SupabaseStorageClient {

    private static final int SIGNED_URL_TTL_SECONDS = 300;

    private final RestClient restClient;
    private final SupabaseStorageProperties properties;

    public SupabaseStorageClient(
            SupabaseStorageProperties properties
    ) {
        this.properties = properties;

        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.url() + "/storage/v1")
                .defaultHeader(
                        "apikey",
                        properties.serviceRoleKey()
                )
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties.serviceRoleKey()
                )
                .build();
    }

    public SignedUploadUrl createSignedAvatarUploadUrl(
            String objectPath
    ) {
        return createSignedUploadUrl(
                properties.avatarBucket(),
                objectPath,
                false
        );
    }

    public SignedUploadUrl createSignedVerificationDocumentUploadUrl(
            String objectPath
    ) {
        return createSignedUploadUrl(
                properties.verificationBucket(),
                objectPath,
                true
        );
    }

    public SignedUploadUrl createSignedListingMediaUploadUrl(
            String objectPath
    ) {
        return createSignedUploadUrl(
                properties.listingMediaBucket(),
                objectPath,
                false
        );
    }

    /** Immutable capability: a still-valid upload URL must not overwrite a scanned document. */
    public SignedUploadUrl createSignedEvidenceUploadUrl(String objectPath) {
        return createSignedUploadUrl(properties.verificationBucket(), objectPath, false);
    }

    /** Bounded private download for server validation; never return file bytes or this URL to callers. */
    public byte[] readVerificationDocument(String objectPath, long expectedBytes) {
        if (expectedBytes < 1 || expectedBytes > 10_485_760 || objectPath == null
                || !objectPath.matches("[A-Za-z0-9/_\\-.]+") || objectPath.contains("..")
                || objectPath.startsWith("/")) throw invalidEvidence();
        URI base = URI.create(properties.url());
        if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null) throw unavailableEvidence();
        URI uri = URI.create(properties.url() + "/storage/v1/object/authenticated/"
                + properties.verificationBucket() + "/" + objectPath);
        if (!base.getHost().equals(uri.getHost()) || uri.getUserInfo() != null) throw unavailableEvidence();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("apikey", properties.serviceRoleKey())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceRoleKey()).GET().build();
            var transfer = client.sendAsync(request, ignored -> new LimitedEvidenceBody(expectedBytes));
            HttpResponse<byte[]> response;
            try { response = transfer.get(15, TimeUnit.SECONDS); }
            catch (TimeoutException | InterruptedException failure) {
                transfer.cancel(true);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw unavailableEvidence();
            }
            if (response.statusCode() != 200 || response.body().length != expectedBytes) throw invalidEvidence();
            return response.body();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ResponseStatusException rejected) throw rejected;
            throw unavailableEvidence();
        }
    }

    static final class LimitedEvidenceBody implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        LimitedEvidenceBody(long limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) { subscription.cancel(); return; }
            for (ByteBuffer chunk : chunks) {
                if (bytes.size() + (long) chunk.remaining() > limit) {
                    subscription.cancel(); result.completeExceptionally(invalidEvidence()); return;
                }
                byte[] content = new byte[chunk.remaining()];
                chunk.get(content); bytes.writeBytes(content);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private static ResponseStatusException invalidEvidence() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "The stored evidence is invalid or unavailable.");
    }

    /** Service-only normalization: client upload capabilities stay non-overwriting. */
    public void storePreparedVerificationImage(String objectPath, String contentType, byte[] prepared) {
        if (prepared == null || prepared.length == 0 || prepared.length > 10_485_760
                || !List.of("image/jpeg", "image/png").contains(contentType)) throw invalidEvidence();
        restClient.post().uri("/object/" + properties.verificationBucket() + "/" + objectPath)
                .header("x-upsert", "true").contentType(MediaType.parseMediaType(contentType))
                .body(prepared).retrieve().toBodilessEntity();
    }
    private static ResponseStatusException unavailableEvidence() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document validation is unavailable. The file has not been confirmed.");
    }

    public StoredObjectInfo getAvatarInfo(
            String objectPath
    ) {
        return objectInfo(
                properties.avatarBucket(),
                objectPath
        );
    }

    public StoredObjectInfo getVerificationDocumentInfo(
            String objectPath
    ) {
        return objectInfo(
                properties.verificationBucket(),
                objectPath
        );
    }

    public StoredObjectInfo getListingMediaInfo(
            String objectPath
    ) {
        return objectInfo(
                properties.listingMediaBucket(),
                objectPath
        );
    }

    public String createSignedAvatarReadUrl(
            String objectPath
    ) {
        return createSignedReadUrl(
                properties.avatarBucket(),
                objectPath,
                "avatar"
        );
    }

    public String createSignedListingMediaReadUrl(
            String objectPath
    ) {
        return createSignedReadUrl(
                properties.listingMediaBucket(),
                objectPath,
                "listing media"
        );
    }

    public void deleteListingMedia(
            String objectPath
    ) {
        restClient.delete()
                .uri(
                        "/object/"
                                + properties.listingMediaBucket()
                                + "/"
                                + objectPath
                )
                .retrieve()
                .toBodilessEntity();
    }

    private SignedUploadUrl createSignedUploadUrl(
            String bucket,
            String objectPath,
            boolean upsert
    ) {
        RestClient.RequestBodySpec request = restClient.post()
                .uri(
                        "/object/upload/sign/"
                                + bucket
                                + "/"
                                + objectPath
                )
                .contentType(MediaType.APPLICATION_JSON);

        /*
         * Supabase reads the upsert option from the x-upsert header.
         * It does not read it from the JSON body.
         *
         * This is enabled for verification documents so that an
         * UPLOADING document can be retried safely using the same path.
         * A document already marked READY is still protected by the
         * verification service and cannot receive another upload URL.
         */
        if (upsert) {
            request.header("x-upsert", "true");
        }

        SignedUploadResponse response = request
                .body(Map.of())
                .retrieve()
                .body(SignedUploadResponse.class);

        if (response == null
                || response.url() == null
                || response.url().isBlank()) {
            throw new IllegalStateException(
                    "Supabase did not return an upload URL."
            );
        }

        return new SignedUploadUrl(
                properties.url()
                        + "/storage/v1"
                        + response.url(),
                objectPath
        );
    }

    private String createSignedReadUrl(
            String bucket,
            String objectPath,
            String label
    ) {
        try {
            SignedReadResponse response = restClient.post()
                    .uri(
                            "/object/sign/"
                                    + bucket
                                    + "/"
                                    + objectPath
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                            Map.of(
                                    "expiresIn",
                                    SIGNED_URL_TTL_SECONDS
                            )
                    )
                    .retrieve()
                    .body(SignedReadResponse.class);

            if (response == null
                    || response.signedURL() == null
                    || response.signedURL().isBlank()) {
                throw new IllegalStateException(
                        "Supabase did not return a "
                                + label
                                + " URL."
                );
            }

            return properties.url()
                    + "/storage/v1"
                    + response.signedURL();

        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return null;
            }

            throw exception;
        }
    }

    private StoredObjectInfo objectInfo(
            String bucket,
            String objectPath
    ) {
        try {
            /*
             * Supabase's /object/info endpoint is a GET endpoint that
             * returns the object size and content type in a JSON body.
             */
            ObjectInfoResponse response = restClient.get()
                    .uri(
                            "/object/info/"
                                    + bucket
                                    + "/"
                                    + objectPath
                    )
                    .retrieve()
                    .body(ObjectInfoResponse.class);

            if (response == null || response.size() == null) {
                return null;
            }

            return new StoredObjectInfo(
                    normalizeContentType(response.contentType()),
                    response.size()
            );

        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return null;
            }

            throw exception;
        }
    }

    private static String normalizeContentType(
            String contentType
    ) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }

        try {
            MediaType parsed =
                    MediaType.parseMediaType(contentType);

            return (
                    parsed.getType()
                            + "/"
                            + parsed.getSubtype()
            ).toLowerCase(Locale.ROOT);

        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record SignedUploadUrl(
            String uploadUrl,
            String objectPath
    ) {
    }

    public record StoredObjectInfo(
            String contentType,
            long sizeBytes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SignedUploadResponse(
            String url
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SignedReadResponse(
            String signedURL
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ObjectInfoResponse(
            Long size,

            @JsonAlias({
                    "content_type",
                    "mimetype"
            })
            String contentType
    ) {
    }
}
