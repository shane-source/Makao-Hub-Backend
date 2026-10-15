package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.PublicListingResponse;
import com.makaohub.backend.property.api.PublicSublessorListingResponse;
import com.makaohub.backend.property.api.RenterDashboardListingsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class RenterDashboardListingService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PublicListingService landlordListingService;
    private final PublicSublessorListingService sublessorListingService;

    public RenterDashboardListingService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            PublicListingService landlordListingService,
            PublicSublessorListingService sublessorListingService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.landlordListingService = landlordListingService;
        this.sublessorListingService = sublessorListingService;
    }

    @Transactional(readOnly = true)
    public RenterDashboardListingsResponse getDashboardListings(
            UUID renterId
    ) {
        requireActiveRenter(renterId);

        List<PublicListingResponse> landlordListings =
                landlordListingService
                        .findPublishedListings()
                        .stream()
                        .toList();

        List<PublicSublessorListingResponse> sublessorListings =
                sublessorListingService
                        .findPublishedListings()
                        .stream()
                        .toList();

        return new RenterDashboardListingsResponse(
                landlordListings,
                sublessorListings
        );
    }

    private void requireActiveRenter(UUID renterId) {
        User user = userRepository.findById(renterId)
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
                    "Only renters can access the renter dashboard."
            );
        }
    }
}