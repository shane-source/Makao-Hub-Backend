package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.notification.service.NotificationService;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.renter.domain.RentalApplicationStatus;
import com.makaohub.backend.renter.domain.SublessorRentalApplication;
import com.makaohub.backend.renter.repository.SublessorRentalApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SublessorApplicationReviewTest {

    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherSublessorId = UUID.randomUUID();
    private final UUID renterId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();

    private UserRepository users;
    private UserRoleRepository roles;
    private SublessorListingRepository listings;
    private SublessorRentalApplicationRepository applications;
    private SublessorApplicationReviewService service;
    private SublessorRentalApplication application;
    private ApplicationApplicantDetails applicantDetails;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(UserRoleRepository.class);
        listings = mock(SublessorListingRepository.class);
        applications = mock(SublessorRentalApplicationRepository.class);
        applicantDetails = mock(ApplicationApplicantDetails.class);
        when(applicantDetails.forApplicant(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ApplicationApplicantDetails.Details(null, null, null));
        service = new SublessorApplicationReviewService(
                users,
                roles,
                listings,
                applications,
                mock(NotificationService.class), applicantDetails
        );

        application = new SublessorRentalApplication(renterId, listingId);
        when(applications.findById(applicationId))
                .thenReturn(Optional.of(application));

        allowSublessor(ownerId);
        allowSublessor(otherSublessorId);
        when(listings.findByIdAndSublessorId(listingId, ownerId))
                .thenReturn(Optional.of(mock(SublessorListing.class)));
        when(listings.findByIdAndSublessorId(
                listingId,
                otherSublessorId
        )).thenReturn(Optional.empty());
    }

    @Test
    void ownedApplicationCanBeReviewedAndRejectedWithReason() {
        service.markUnderReview(ownerId, applicationId);
        var response = service.reject(
                ownerId,
                applicationId,
                "The room is unavailable for that move-in date."
        );

        assertEquals(RentalApplicationStatus.REJECTED, response.status());
        assertEquals(
                "The room is unavailable for that move-in date.",
                response.rejectionReason()
        );
    }

    @Test
    void anotherSublessorCannotReviewTheApplication() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> service.markUnderReview(
                        otherSublessorId,
                        applicationId
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals(RentalApplicationStatus.SUBMITTED, application.getStatus());
        org.mockito.Mockito.verifyNoInteractions(applicantDetails);
    }

    @Test
    void accountWithoutSublessorRoleCannotReviewTheApplication() {
        UUID renterOnlyId = UUID.randomUUID();
        User user = mock(User.class);
        when(users.findById(renterOnlyId)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(roles.existsByUser_IdAndRole_Name(
                renterOnlyId,
                RoleName.SUBLESSOR
        )).thenReturn(false);

        var error = assertThrows(
                ResponseStatusException.class,
                () -> service.markUnderReview(
                        renterOnlyId,
                        applicationId
                )
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        assertEquals(RentalApplicationStatus.SUBMITTED, application.getStatus());
    }

    private void allowSublessor(UUID userId) {
        User user = mock(User.class);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(roles.existsByUser_IdAndRole_Name(
                userId,
                RoleName.SUBLESSOR
        )).thenReturn(true);
    }
}
