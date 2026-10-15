package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.CreateListingMediaUploadRequest;
import com.makaohub.backend.property.api.ListingMediaResponse;
import com.makaohub.backend.property.api.ListingMediaUploadResponse;
import com.makaohub.backend.property.domain.ListingMediaType;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.domain.SublessorListingMedia;
import com.makaohub.backend.property.repository.SublessorListingMediaRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class SublessorListingMediaService {

    private static final long MAX_IMAGES_PER_LISTING = 20;
    private static final long MAX_VIDEOS_PER_LISTING = 1;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SublessorListingRepository listingRepository;
    private final SublessorListingMediaRepository mediaRepository;
    private final SupabaseStorageClient storageClient;

    public SublessorListingMediaService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SublessorListingRepository listingRepository,
            SublessorListingMediaRepository mediaRepository,
            SupabaseStorageClient storageClient
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.mediaRepository = mediaRepository;
        this.storageClient = storageClient;
    }

    @Transactional
    public ListingMediaUploadResponse createUploadUrl(
            UUID authenticatedSublessorId,
            UUID listingId,
            CreateListingMediaUploadRequest request
    ) {
        requireActiveSublessor(authenticatedSublessorId);

        SublessorListing listing =
                listingRepository.findByIdAndSublessorId(
                        listingId,
                        authenticatedSublessorId
                ).orElseThrow(
                        SublessorListingMediaService::listingNotFound
                );

        requireDraft(listing);
        validateContentType(request);

        if (mediaRepository
                .existsBySublessorListingIdAndSortOrder(
                        listingId,
                        request.sortOrder()
                )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "That media position is already in use."
            );
        }

        long existingMedia =
                mediaRepository
                        .countBySublessorListingIdAndMediaType(
                                listingId,
                                request.mediaType()
                        );

        if (request.mediaType() == ListingMediaType.IMAGE
                && existingMedia >= MAX_IMAGES_PER_LISTING) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "A listing cannot contain more than 20 images."
            );
        }

        if (request.mediaType() == ListingMediaType.VIDEO
                && existingMedia >= MAX_VIDEOS_PER_LISTING) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "A listing cannot contain more than one video."
            );
        }

        String objectPath = buildObjectPath(
                authenticatedSublessorId,
                listingId,
                request.contentType()
        );

        SublessorListingMedia media =
                mediaRepository.save(
                        new SublessorListingMedia(
                                listingId,
                                request.mediaType(),
                                objectPath,
                                request.contentType(),
                                request.sizeBytes(),
                                request.sortOrder()
                        )
                );

        SupabaseStorageClient.SignedUploadUrl signedUpload =
                storageClient.createSignedListingMediaUploadUrl(
                        objectPath
                );

        return new ListingMediaUploadResponse(
                media.getId(),
                media.getMediaType(),
                media.getContentType(),
                media.getSizeBytes(),
                media.getSortOrder(),
                signedUpload.uploadUrl()
        );
    }

    @Transactional
    public ListingMediaResponse confirmUpload(
            UUID authenticatedSublessorId,
            UUID listingId,
            UUID mediaId
    ) {
        requireActiveSublessor(authenticatedSublessorId);

        SublessorListing listing =
                listingRepository.findByIdAndSublessorId(
                        listingId,
                        authenticatedSublessorId
                ).orElseThrow(
                        SublessorListingMediaService::listingNotFound
                );

        requireDraft(listing);

        SublessorListingMedia media =
                mediaRepository.findByIdAndSublessorListingId(
                        mediaId,
                        listingId
                ).orElseThrow(
                        SublessorListingMediaService::mediaNotFound
                );

        SupabaseStorageClient.StoredObjectInfo objectInfo =
                storageClient.getListingMediaInfo(
                        media.getObjectPath()
                );

        if (objectInfo == null
                || objectInfo.sizeBytes() != media.getSizeBytes()
                || objectInfo.contentType() == null
                || !objectInfo.contentType().equalsIgnoreCase(
                media.getContentType()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Uploaded media does not match the requested metadata."
            );
        }

        media.confirm();
        mediaRepository.save(media);

        return toResponse(media);
    }

    @Transactional(readOnly = true)
    public List<ListingMediaResponse> findForListing(
            UUID authenticatedSublessorId,
            UUID listingId
    ) {
        requireActiveSublessor(authenticatedSublessorId);

        listingRepository.findByIdAndSublessorId(
                listingId,
                authenticatedSublessorId
        ).orElseThrow(
                SublessorListingMediaService::listingNotFound
        );

        return mediaRepository
                .findAllBySublessorListingIdOrderBySortOrderAsc(
                        listingId
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deleteMedia(
            UUID authenticatedSublessorId,
            UUID listingId,
            UUID mediaId
    ) {
        requireActiveSublessor(authenticatedSublessorId);

        SublessorListing listing =
                listingRepository.findByIdAndSublessorId(
                        listingId,
                        authenticatedSublessorId
                ).orElseThrow(
                        SublessorListingMediaService::listingNotFound
                );

        requireDraft(listing);

        SublessorListingMedia media =
                mediaRepository.findByIdAndSublessorListingId(
                        mediaId,
                        listingId
                ).orElseThrow(
                        SublessorListingMediaService::mediaNotFound
                );

        storageClient.deleteListingMedia(
                media.getObjectPath()
        );

        mediaRepository.delete(media);
    }

    private ListingMediaResponse toResponse(
            SublessorListingMedia media
    ) {
        return new ListingMediaResponse(
                media.getId(),
                media.getSublessorListingId(),
                media.getMediaType(),
                media.getContentType(),
                media.getSizeBytes(),
                media.getSortOrder(),
                media.getCreatedAt(),
                media.getConfirmedAt() != null
        );
    }

    private void requireDraft(SublessorListing listing) {
        if (listing.getStatus() != ListingStatus.DRAFT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Media can only be changed while the listing is a draft."
            );
        }
    }

    private void validateContentType(
            CreateListingMediaUploadRequest request
    ) {
        String contentType = request.contentType()
                .strip()
                .toLowerCase(Locale.ROOT);

        boolean valid = switch (request.mediaType()) {
            case IMAGE -> contentType.equals("image/jpeg")
                    || contentType.equals("image/png")
                    || contentType.equals("image/webp");

            case VIDEO -> contentType.equals("video/mp4");
        };

        if (!valid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Content type does not match the media type."
            );
        }
    }

    private String buildObjectPath(
            UUID sublessorId,
            UUID listingId,
            String contentType
    ) {
        return "sublessor/"
                + sublessorId
                + "/"
                + listingId
                + "/"
                + UUID.randomUUID()
                + extensionFor(contentType);
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported media content type."
            );
        };
    }

    private void requireActiveSublessor(
            UUID authenticatedSublessorId
    ) {
        User user = userRepository.findById(
                authenticatedSublessorId
        ).orElseThrow(
                SublessorListingMediaService::accountUnavailable
        );

        if (user.getAccountStatus()
                != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                authenticatedSublessorId,
                RoleName.SUBLESSOR
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only sublessors can manage room media."
            );
        }
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Sublessor listing not found."
        );
    }

    private static ResponseStatusException mediaNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Listing media not found."
        );
    }

    private static ResponseStatusException accountUnavailable() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "The account is unavailable."
        );
    }
}
