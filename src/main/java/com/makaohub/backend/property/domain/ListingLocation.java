package com.makaohub.backend.property.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "listing_locations")
public class ListingLocation {

    @Id
    @Column(name = "listing_id")
    private UUID listingId;

    @Column(name = "area_label", nullable = false, length = 150)
    private String areaLabel;

    @Column(name = "exact_coordinates_ciphertext", nullable = false)
    private byte[] exactCoordinatesCiphertext;

    @Column(name = "approximate_latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal approximateLatitude;

    @Column(name = "approximate_longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal approximateLongitude;

    @Column(name = "approximate_radius_meters", nullable = false)
    private short approximateRadiusMeters;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingLocation() {
    }

    public ListingLocation(
            UUID listingId,
            String areaLabel,
            byte[] exactCoordinatesCiphertext,
            BigDecimal approximateLatitude,
            BigDecimal approximateLongitude,
            short approximateRadiusMeters
    ) {
        this.listingId = Objects.requireNonNull(listingId);
        update(
                areaLabel,
                exactCoordinatesCiphertext,
                approximateLatitude,
                approximateLongitude,
                approximateRadiusMeters
        );
    }

    public void update(
            String areaLabel,
            byte[] exactCoordinatesCiphertext,
            BigDecimal approximateLatitude,
            BigDecimal approximateLongitude,
            short approximateRadiusMeters
    ) {
        this.areaLabel = Objects.requireNonNull(areaLabel).strip();
        this.exactCoordinatesCiphertext =
                Objects.requireNonNull(exactCoordinatesCiphertext).clone();
        this.approximateLatitude =
                Objects.requireNonNull(approximateLatitude);
        this.approximateLongitude =
                Objects.requireNonNull(approximateLongitude);
        this.approximateRadiusMeters = approximateRadiusMeters;
    }

    public String getAreaLabel() {
        return areaLabel;
    }

    public byte[] getExactCoordinatesCiphertext() {
        return exactCoordinatesCiphertext.clone();
    }
}