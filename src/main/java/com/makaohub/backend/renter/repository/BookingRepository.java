package com.makaohub.backend.renter.repository;

import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingListingKind;
import com.makaohub.backend.renter.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    Optional<Booking> findByListingKindAndSourceApplicationId(
            BookingListingKind listingKind,
            UUID sourceApplicationId
    );
    List<Booking> findAllByRenterIdOrderByCreatedAtDesc(UUID renterId);
    List<Booking> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
    Optional<Booking> findByIdAndRenterId(UUID id, UUID renterId);
    Optional<Booking> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT booking FROM Booking booking
            WHERE booking.id = :bookingId
            """)
    Optional<Booking> findByIdForUpdate(@Param("bookingId") UUID bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT booking FROM Booking booking
            WHERE booking.id = :bookingId AND booking.renterId = :renterId
            """)
    Optional<Booking> findByIdAndRenterIdForUpdate(
            @Param("bookingId") UUID bookingId,
            @Param("renterId") UUID renterId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT booking FROM Booking booking
            WHERE booking.id = :bookingId AND booking.ownerId = :ownerId
            """)
    Optional<Booking> findByIdAndOwnerIdForUpdate(
            @Param("bookingId") UUID bookingId,
            @Param("ownerId") UUID ownerId
    );
    List<Booking> findAllByStatusOrderByCreatedAtAsc(BookingStatus status);
}
