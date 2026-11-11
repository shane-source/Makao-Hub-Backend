ALTER TABLE payment_intents ADD COLUMN offer_code VARCHAR(20);
ALTER TABLE payment_intents ADD CONSTRAINT payment_intents_offer_code_check CHECK (
    (payment_type = 'PLAN_UPGRADE' AND (offer_code IS NULL OR offer_code IN ('PRO_30D', 'PRO_MAX_30D'))) OR
    (payment_type = 'BOOST' AND (offer_code IS NULL OR offer_code IN ('BOOST_7D', 'BOOST_14D'))) OR
    (payment_type NOT IN ('PLAN_UPGRADE', 'BOOST') AND offer_code IS NULL)
);

CREATE TABLE landlord_offer_grants (
    payment_intent_id UUID PRIMARY KEY REFERENCES payment_intents(id) ON DELETE RESTRICT,
    landlord_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    listing_id UUID REFERENCES listings(id) ON DELETE RESTRICT,
    offer_code VARCHAR(20) NOT NULL CHECK (offer_code IN ('PRO_30D', 'PRO_MAX_30D', 'BOOST_7D', 'BOOST_14D')),
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > starts_at),
    CHECK ((offer_code LIKE 'BOOST_%' AND listing_id IS NOT NULL) OR
           (offer_code NOT LIKE 'BOOST_%' AND listing_id IS NULL))
);
CREATE INDEX landlord_offer_grants_plan_lookup ON landlord_offer_grants(landlord_id, expires_at)
    WHERE listing_id IS NULL;
CREATE INDEX landlord_offer_grants_boost_lookup ON landlord_offer_grants(listing_id, expires_at)
    WHERE listing_id IS NOT NULL;
