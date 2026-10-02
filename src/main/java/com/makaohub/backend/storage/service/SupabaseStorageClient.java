package com.makaohub.backend.storage.service;

import com.makaohub.backend.storage.config.SupabaseStorageProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Service
public class SupabaseStorageClient {

    private static final int AVATAR_URL_TTL_SECONDS = 300;

    private final RestClient restClient;
    private final SupabaseStorageProperties properties;

    public SupabaseStorageClient(
            SupabaseStorageProperties properties
    ) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.url() + "/storage/v1")
                .defaultHeader("apikey", properties.serviceRoleKey())
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
                objectPath
        );
    }

    public SignedUploadUrl createSignedVerificationDocumentUploadUrl(
            String objectPath
    ) {
        return createSignedUploadUrl(
                properties.verificationBucket(),
                objectPath
        );
    }

    public StoredObjectInfo getAvatarInfo(String objectPath) {
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

    public String createSignedAvatarReadUrl(String objectPath) {
        try {
            SignedReadResponse response = restClient.post()
                    .uri(
                            "/object/sign/"
                                    + properties.avatarBucket()
                                    + "/"
                                    + objectPath
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("expiresIn", AVATAR_URL_TTL_SECONDS))
                    .retrieve()
                    .body(SignedReadResponse.class);

            if (response == null || response.signedURL() == null) {
                throw new IllegalStateException(
                        "Supabase did not return an avatar URL"
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

    private SignedUploadUrl createSignedUploadUrl(
            String bucket,
            String objectPath
    ) {
        SignedUploadResponse response = restClient.post()
                .uri("/object/upload/sign/" + bucket + "/" + objectPath)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(SignedUploadResponse.class);

        if (response == null || response.url() == null) {
            throw new IllegalStateException(
                    "Supabase did not return an upload URL"
            );
        }

        return new SignedUploadUrl(
                properties.url() + "/storage/v1" + response.url(),
                objectPath
        );
    }

    private StoredObjectInfo objectInfo(
            String bucket,
            String objectPath
    ) {
        try {
            ResponseEntity<Void> response = restClient.head()
                    .uri("/object/info/" + bucket + "/" + objectPath)
                    .retrieve()
                    .toBodilessEntity();

            MediaType contentType = response.getHeaders()
                    .getContentType();

            String normalizedContentType = contentType == null
                    ? null
                    : contentType.getType()
                    + "/"
                    + contentType.getSubtype();

            return new StoredObjectInfo(
                    normalizedContentType,
                    response.getHeaders().getContentLength()
            );
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return null;
            }

            throw exception;
        }
    }

    public record SignedUploadUrl(String uploadUrl, String objectPath) {
    }

    public record StoredObjectInfo(
            String contentType,
            long sizeBytes
    ) {
    }

    private record SignedUploadResponse(String url) {
    }

    private record SignedReadResponse(String signedURL) {
    }
}