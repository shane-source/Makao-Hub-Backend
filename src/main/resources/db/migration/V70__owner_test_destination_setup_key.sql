-- Existing Staff-configured TEST destinations remain valid; only new owner-created rows carry a setup key.
ALTER TABLE paystack_test_destinations ADD COLUMN setup_key UUID;
