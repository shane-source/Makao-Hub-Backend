package com.makaohub.backend.payment.service;

import com.makaohub.backend.payment.domain.PaymentIntent;

/** Provider boundary for starting a payment collection request. */
public interface PaymentProvider {

    ProviderPaymentStart start(PaymentIntent intent);
}
