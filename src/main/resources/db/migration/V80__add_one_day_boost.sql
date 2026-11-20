-- Adds the 1-day boost offer for landlords (BOOST_1D) and sublessors (SUB_BOOST_1D).
ALTER TABLE payment_intents DROP CONSTRAINT IF EXISTS payment_intents_offer_code_check;
ALTER TABLE payment_intents ADD CONSTRAINT payment_intents_offer_code_check CHECK (
    (payment_type = 'PLAN_UPGRADE' AND (offer_code IS NULL OR offer_code IN
        ('PRO_30D', 'PRO_MAX_30D', 'SUB_PRO_30D', 'SUB_PRO_MAX_30D'))) OR
    (payment_type = 'BOOST' AND (offer_code IS NULL OR offer_code IN
        ('BOOST_1D', 'BOOST_7D', 'BOOST_14D', 'SUB_BOOST_1D', 'SUB_BOOST_7D', 'SUB_BOOST_14D'))) OR
    (payment_type NOT IN ('PLAN_UPGRADE', 'BOOST') AND offer_code IS NULL)
);

ALTER TABLE landlord_offer_grants DROP CONSTRAINT IF EXISTS landlord_offer_grants_offer_code_check;
ALTER TABLE landlord_offer_grants ADD CONSTRAINT landlord_offer_grants_offer_code_check CHECK (
    offer_code IN ('PRO_30D', 'PRO_MAX_30D', 'BOOST_1D', 'BOOST_7D', 'BOOST_14D'));

ALTER TABLE sublessor_offer_grants DROP CONSTRAINT IF EXISTS sublessor_offer_grants_offer_code_check;
ALTER TABLE sublessor_offer_grants ADD CONSTRAINT sublessor_offer_grants_offer_code_check CHECK (
    offer_code IN ('SUB_PRO_30D', 'SUB_PRO_MAX_30D', 'SUB_BOOST_1D', 'SUB_BOOST_7D', 'SUB_BOOST_14D'));
