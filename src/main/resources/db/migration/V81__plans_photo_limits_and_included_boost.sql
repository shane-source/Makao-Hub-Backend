-- 1. Grants can now exist without a payment (a boost included with Pro Max).
ALTER TABLE landlord_offer_grants DROP CONSTRAINT IF EXISTS landlord_offer_grants_pkey;
ALTER TABLE landlord_offer_grants ADD COLUMN IF NOT EXISTS id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE landlord_offer_grants ADD PRIMARY KEY (id);
ALTER TABLE landlord_offer_grants ALTER COLUMN payment_intent_id DROP NOT NULL;
ALTER TABLE landlord_offer_grants ADD CONSTRAINT landlord_offer_grants_payment_unique UNIQUE (payment_intent_id);

ALTER TABLE sublessor_offer_grants DROP CONSTRAINT IF EXISTS sublessor_offer_grants_pkey;
ALTER TABLE sublessor_offer_grants ADD COLUMN IF NOT EXISTS id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE sublessor_offer_grants ADD PRIMARY KEY (id);
ALTER TABLE sublessor_offer_grants ALTER COLUMN payment_intent_id DROP NOT NULL;
ALTER TABLE sublessor_offer_grants ADD CONSTRAINT sublessor_offer_grants_payment_unique UNIQUE (payment_intent_id);

-- 2. One included 7-day boost per Pro Max purchase.
CREATE TABLE plan_boost_credits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_intent_id UUID NOT NULL UNIQUE REFERENCES payment_intents(id) ON DELETE RESTRICT,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    sublessor BOOLEAN NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    redeemed_at TIMESTAMPTZ,
    redeemed_listing_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX plan_boost_credits_owner_idx ON plan_boost_credits(owner_id, sublessor) WHERE redeemed_at IS NULL;
ALTER TABLE plan_boost_credits ENABLE ROW LEVEL SECURITY;

-- 3. Pro Max has no photo cap, so widen the position limit (plans enforce the real limit in code).
ALTER TABLE listing_media DROP CONSTRAINT IF EXISTS listing_media_sort_order_check;
ALTER TABLE listing_media ADD CONSTRAINT listing_media_sort_order_check CHECK (sort_order >= 0 AND sort_order <= 250);
ALTER TABLE sublessor_listing_media DROP CONSTRAINT IF EXISTS sublessor_listing_media_sort_order_check;
ALTER TABLE sublessor_listing_media ADD CONSTRAINT sublessor_listing_media_sort_order_check CHECK (sort_order >= 0 AND sort_order <= 250);
