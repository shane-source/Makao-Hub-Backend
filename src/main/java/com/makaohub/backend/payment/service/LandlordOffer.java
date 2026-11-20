package com.makaohub.backend.payment.service;

import com.makaohub.backend.payment.domain.PaymentType;
import java.time.Duration;

/** Fixed, server-priced prepaid test offers. Starter is the free default. */
public enum LandlordOffer {
    // Plans: Free = FREE_PHOTO_LIMIT photos; Pro (KSh 999) = 5 photos; Pro Max (KSh 1,500) = unlimited photos
    // and one included 7-day boost. Last two numbers: photos per listing, included boosts.
    PRO_30D(PaymentType.PLAN_UPGRADE, 99900, 30, 5, 0, 5, 0),
    PRO_MAX_30D(PaymentType.PLAN_UPGRADE, 150000, 30, 20, 0, Integer.MAX_VALUE, 1),
    // Boost prices are in KES minor units (x100). Reach is an estimate of renters, not a guarantee.
    BOOST_1D(PaymentType.BOOST, 10000, 1, 0, 500, 0, 0),
    BOOST_7D(PaymentType.BOOST, 30000, 7, 0, 3500, 0, 0),
    BOOST_14D(PaymentType.BOOST, 50000, 14, 0, 7000, 0, 0);

    public static final int FREE_PHOTO_LIMIT = 2;

    public final PaymentType paymentType;
    public final long amountMinor;
    public final Duration duration;
    public final int listingLimit;
    public final int estimatedReach;
    public final int photoLimit;
    public final int includedBoosts;

    LandlordOffer(PaymentType paymentType, long amountMinor, long days, int listingLimit, int estimatedReach,
            int photoLimit, int includedBoosts) {
        this.paymentType = paymentType;
        this.amountMinor = amountMinor;
        this.duration = Duration.ofDays(days);
        this.listingLimit = listingLimit;
        this.estimatedReach = estimatedReach;
        this.photoLimit = photoLimit;
        this.includedBoosts = includedBoosts;
    }
}
