package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingListingKind;
import com.makaohub.backend.renter.domain.RentalApplication;
import com.makaohub.backend.renter.domain.RentalApplicationStatus;
import com.makaohub.backend.renter.domain.SublessorRentalApplication;
import com.makaohub.backend.renter.repository.BookingRepository;
import com.makaohub.backend.renter.repository.RentalApplicationRepository;
import com.makaohub.backend.renter.repository.SublessorRentalApplicationRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Creates immutable booking snapshots from approved renter applications. */
@Service
public class BookingCreationService {

    private static final long KES_MINOR_UNITS = 100;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository landlordListingRepository;
    private final SublessorListingRepository sublessorListingRepository;
    private final RentalApplicationRepository rentalApplicationRepository;
    private final SublessorRentalApplicationRepository sublessorApplicationRepository;
    private final BookingRepository bookingRepository;
    private final BookingDepositPricingService depositPricingService;
    private final BookingCommissionService commissionService;
    private final RoleVerificationGuard verificationGuard;

    public BookingCreationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository landlordListingRepository,
            SublessorListingRepository sublessorListingRepository,
            RentalApplicationRepository rentalApplicationRepository,
            SublessorRentalApplicationRepository sublessorApplicationRepository,
            BookingRepository bookingRepository,
            BookingDepositPricingService depositPricingService,
            BookingCommissionService commissionService,
            RoleVerificationGuard verificationGuard
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.landlordListingRepository = landlordListingRepository;
        this.sublessorListingRepository = sublessorListingRepository;
        this.rentalApplicationRepository = rentalApplicationRepository;
        this.sublessorApplicationRepository = sublessorApplicationRepository;
        this.bookingRepository = bookingRepository;
        this.depositPricingService = depositPricingService;
        this.commissionService = commissionService;
        this.verificationGuard = verificationGuard;
    }

    /** The deposit must come from trusted server-side pricing policy. */
    @Transactional
    public Booking createForLandlordApplication(
            UUID renterId,
            UUID applicationId
    ) {
        requireActiveRenter(renterId);
        verificationGuard.requireVerified(renterId, RoleName.RENTER);
        RentalApplication application = rentalApplicationRepository
                .findByIdAndRenterId(applicationId, renterId)
                .orElseThrow(BookingCreationService::applicationNotFound);
        requireApproved(application.getStatus());

        Listing listing = landlordListingRepository
                .findByIdAndStatus(application.getListingId(), ListingStatus.PUBLISHED)
                .orElseThrow(BookingCreationService::listingNotFound);
        Booking existing = bookingRepository
                .findByListingKindAndSourceApplicationId(
                        BookingListingKind.LANDLORD_LISTING, application.getId()
                )
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        long rentAmountMinor = toMinorUnits(listing.getMonthlyRentKes());
        long depositAmountMinor = depositPricingService.depositAmountMinor();
        long commissionAmountMinor = commissionService.commissionAmountMinor(
                rentAmountMinor,
                depositAmountMinor
        );

        return bookingRepository.save(new Booking(
                renterId,
                listing.getLandlordId(),
                listing.getId(),
                BookingListingKind.LANDLORD_LISTING,
                application.getId(),
                rentAmountMinor,
                depositAmountMinor,
                commissionAmountMinor,
                Booking.KES
        ));
    }

    @Transactional
    public Booking createForSublessorApplication(
            UUID renterId,
            UUID applicationId
    ) {
        requireActiveRenter(renterId);
        verificationGuard.requireVerified(renterId, RoleName.RENTER);
        SublessorRentalApplication application = sublessorApplicationRepository
                .findByIdAndRenterId(applicationId, renterId)
                .orElseThrow(BookingCreationService::applicationNotFound);
        requireApproved(application.getStatus());

        SublessorListing listing = sublessorListingRepository
                .findById(application.getSublessorListingId())
                .filter(value -> value.getStatus() == ListingStatus.PUBLISHED)
                .orElseThrow(BookingCreationService::listingNotFound);
        Booking existing = bookingRepository
                .findByListingKindAndSourceApplicationId(
                        BookingListingKind.SUBLESSOR_LISTING, application.getId()
                )
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        long rentAmountMinor = toMinorUnits(listing.getMonthlyRentKes());
        long depositAmountMinor = depositPricingService.depositAmountMinor();
        long commissionAmountMinor = commissionService.commissionAmountMinor(
                rentAmountMinor,
                depositAmountMinor
        );

        return bookingRepository.save(new Booking(
                renterId,
                listing.getSublessorId(),
                listing.getId(),
                BookingListingKind.SUBLESSOR_LISTING,
                application.getId(),
                rentAmountMinor,
                depositAmountMinor,
                commissionAmountMinor,
                Booking.KES
        ));
    }

    private void requireActiveRenter(UUID renterId) {
        User user = userRepository.findById(renterId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account is unavailable."
                ));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Account is not active."
            );
        }
        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                renterId, RoleName.RENTER
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Only renters can create bookings."
            );
        }
    }

    private static void requireApproved(RentalApplicationStatus status) {
        if (status != RentalApplicationStatus.APPROVED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only approved applications can create bookings."
            );
        }
    }

    private static long toMinorUnits(long rentKes) {
        try {
            return Math.multiplyExact(rentKes, KES_MINOR_UNITS);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The listing rent is outside the supported payment range."
            );
        }
    }

    private static ResponseStatusException applicationNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found.");
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Published listing not found.");
    }
}
