ALTER TABLE payment_intents DROP CONSTRAINT payment_intents_offer_code_check;
ALTER TABLE payment_intents ADD CONSTRAINT payment_intents_offer_code_check CHECK (
    (payment_type = 'PLAN_UPGRADE' AND (offer_code IS NULL OR offer_code IN
        ('PRO_30D', 'PRO_MAX_30D', 'SUB_PRO_30D', 'SUB_PRO_MAX_30D'))) OR
    (payment_type = 'BOOST' AND (offer_code IS NULL OR offer_code IN
        ('BOOST_7D', 'BOOST_14D', 'SUB_BOOST_7D', 'SUB_BOOST_14D'))) OR
    (payment_type NOT IN ('PLAN_UPGRADE', 'BOOST') AND offer_code IS NULL)
);

CREATE TABLE sublessor_offer_grants (
    payment_intent_id UUID PRIMARY KEY REFERENCES payment_intents(id) ON DELETE RESTRICT,
    sublessor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    listing_id UUID REFERENCES sublessor_listings(id) ON DELETE RESTRICT,
    offer_code VARCHAR(20) NOT NULL CHECK (offer_code IN
        ('SUB_PRO_30D', 'SUB_PRO_MAX_30D', 'SUB_BOOST_7D', 'SUB_BOOST_14D')),
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > starts_at),
    CHECK ((offer_code LIKE 'SUB_BOOST_%' AND listing_id IS NOT NULL) OR
           (offer_code NOT LIKE 'SUB_BOOST_%' AND listing_id IS NULL))
);
CREATE INDEX sublessor_offer_grants_plan_lookup ON sublessor_offer_grants(sublessor_id, expires_at)
    WHERE listing_id IS NULL;
CREATE INDEX sublessor_offer_grants_boost_lookup ON sublessor_offer_grants(listing_id, expires_at)
    WHERE listing_id IS NOT NULL;

CREATE TABLE sublessor_listing_view_counts (
    listing_id UUID PRIMARY KEY REFERENCES sublessor_listings(id) ON DELETE CASCADE,
    detail_opens BIGINT NOT NULL DEFAULT 0 CHECK (detail_opens >= 0)
);
