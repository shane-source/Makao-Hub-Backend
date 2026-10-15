package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.CreateListingMediaUploadRequest;
import com.makaohub.backend.property.api.ListingMediaResponse;
import com.makaohub.backend.property.api.ListingMediaUploadResponse;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingMedia;
import com.makaohub.backend.property.domain.ListingMediaType;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingMediaRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class LandlordListingMediaService {

    private static final long MAX_IMAGES_PER_LISTING = 20;
    private static final long MAX_VIDEOS_PER_LISTING = 1;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository listingRepository;
    private final ListingMediaRepository mediaRepository;
    private final SupabaseStorageClient storageClient;

    public LandlordListingMediaService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository listingRepository,
            ListingMediaRepository mediaRepository,
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
            UUID landlordId,
            UUID listingId,
            CreateListingMediaUploadRequest request
    ) {
        requireActiveLandlord(landlordId);

        Listing listing = listingRepository
                .findByIdAndLandlordId(listingId, landlordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Listing not found."
                ));

        requireDraft(listing);

        validateContentType(request);

        if (mediaRepository.existsByListingIdAndSortOrder(
                listingId,
                request.sortOrder()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "That media position is already in use."
            );
        }

        long existingMedia = mediaRepository.countByListingIdAndMediaType(
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
                landlordId,
                listingId,
                request.contentType()
        );

        ListingMedia media = mediaRepository.save(
                new ListingMedia(
                        listingId,
                        request.mediaType(),
                        objectPath,
                        request.contentType(),
                        request.sizeBytes(),
                        request.sortOrder()
                )
        );

        SupabaseStorageClient.SignedUploadUrl signedUpload =
                storageClient.createSignedListingMediaUploadUrl(objectPath);

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
            UUID landlordId,
            UUID listingId,
            UUID mediaId
    ) {
        requireActiveLandlord(landlordId);

        Listing listing = listingRepository
                .findByIdAndLandlordId(listingId, landlordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Listing not found."
                ));

        requireDraft(listing);

        ListingMedia media = mediaRepository
                .findById(mediaId)
                .filter(item -> item.getListingId().equals(listingId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Media not found."
                ));

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
            UUID landlordId,
            UUID listingId
    ) {
        requireActiveLandlord(landlordId);

        listingRepository
                .findByIdAndLandlordId(listingId, landlordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Listing not found."
                ));

        return mediaRepository
                .findAllByListingIdOrderBySortOrderAsc(listingId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deleteMedia(
            UUID landlordId,
            UUID listingId,
            UUID mediaId
    ) {
        requireActiveLandlord(landlordId);

        Listing listing = listingRepository
                .findByIdAndLandlordId(listingId, landlordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Listing not found."
                ));

        requireDraft(listing);

        ListingMedia media = mediaRepository
                .findById(mediaId)
                .filter(item -> item.getListingId().equals(listingId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Media not found."
                ));

        storageClient.deleteListingMedia(media.getObjectPath());
        mediaRepository.delete(media);
    }

    private ListingMediaResponse toResponse(ListingMedia media) {
        return new ListingMediaResponse(
                media.getId(),
                media.getListingId(),
                media.getMediaType(),
                media.getContentType(),
                media.getSizeBytes(),
                media.getSortOrder(),
                media.getCreatedAt(),
                media.getConfirmedAt() != null
        );
    }

    private void requireDraft(Listing listing) {
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
        String contentType = request.contentType();

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
            UUID landlordId,
            UUID listingId,
            String contentType
    ) {
        return landlordId
                + "/"
                + listingId
                + "/"
                + UUID.randomUUID()
                + extensionFor(contentType);
    }

    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported media type."
            );
        };
    }

    private void requireActiveLandlord(UUID landlordId) {
        User user = userRepository
                .findById(landlordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Account is unavailable."
                ));

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account is not active."
            );
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                landlordId,
                RoleName.LANDLORD
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only landlords can manage listing media."
            );
        }
    }
}
