package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.PublicListingResponse;
import com.makaohub.backend.property.api.SavedListingResponse;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SavedListing;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SavedListingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class SavedListingService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository listingRepository;
    private final SavedListingRepository savedListingRepository;
    private final PublicListingService publicListingService;

    public SavedListingService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository listingRepository,
            SavedListingRepository savedListingRepository,
            PublicListingService publicListingService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.savedListingRepository = savedListingRepository;
        this.publicListingService = publicListingService;
    }

    @Transactional
    public SavedListingResponse save(
            UUID renterId,
            UUID listingId
    ) {
        requireActiveRenter(renterId);

        Listing listing = listingRepository
                .findByIdAndStatus(
                        listingId,
                        ListingStatus.PUBLISHED
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Published listing not found."
                ));

        if (savedListingRepository.existsByUserIdAndListingId(
                renterId,
                listing.getId()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Listing is already saved."
            );
        }

        SavedListing savedListing = savedListingRepository.save(
                new SavedListing(renterId, listingId)
        );

        return new SavedListingResponse(
                savedListing.getListingId(),
                savedListing.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<SavedListingResponse> findSaved(
            UUID renterId
    ) {
        requireActiveRenter(renterId);

        return savedListingRepository
                .findAllByUserIdOrderByCreatedAtDesc(renterId)
                .stream()
                .map(savedListing -> new SavedListingResponse(
                        savedListing.getListingId(),
                        savedListing.getCreatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PublicListingResponse> compare(
            UUID renterId,
            List<UUID> listingIds
    ) {
        requireActiveRenter(renterId);

        if (listingIds == null
                || listingIds.size() != 2
                || listingIds.get(0).equals(listingIds.get(1))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Select exactly two different saved listings."
            );
        }

        return listingIds.stream()
                .map(listingId -> {
                    if (!savedListingRepository
                            .existsByUserIdAndListingId(
                                    renterId,
                                    listingId
                            )) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Saved listing not found."
                        );
                    }

                    return publicListingService
                            .findPublishedListing(listingId);
                })
                .toList();
    }

    @Transactional
    public void remove(
            UUID renterId,
            UUID listingId
    ) {
        requireActiveRenter(renterId);

        savedListingRepository.deleteByUserIdAndListingId(
                renterId,
                listingId
        );
    }

    private void requireActiveRenter(UUID renterId) {
        User user = userRepository
                .findById(renterId)
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
                renterId,
                RoleName.RENTER
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only renters can manage saved listings."
            );
        }
    }
}