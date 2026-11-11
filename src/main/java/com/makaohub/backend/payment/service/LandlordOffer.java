package com.makaohub.backend.payment.service;

import com.makaohub.backend.payment.domain.PaymentType;
import java.time.Duration;

/** Fixed, server-priced prepaid test offers. Starter is the free default. */
public enum LandlordOffer {
    PRO_30D(PaymentType.PLAN_UPGRADE, 49900, 30, 5),
    PRO_MAX_30D(PaymentType.PLAN_UPGRADE, 149900, 30, 20),
    BOOST_7D(PaymentType.BOOST, 29900, 7, 0),
    BOOST_14D(PaymentType.BOOST, 49900, 14, 0);

    public final PaymentType paymentType;
    public final long amountMinor;
    public final Duration duration;
    public final int listingLimit;

    LandlordOffer(PaymentType paymentType, long amountMinor, long days, int listingLimit) {
        this.paymentType = paymentType;
        this.amountMinor = amountMinor;
        this.duration = Duration.ofDays(days);
        this.listingLimit = listingLimit;
    }
}
