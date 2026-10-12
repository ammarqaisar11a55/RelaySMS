-- Safe credential rotation. When a device rotates its token and the relay's answer is lost on the
-- way back, the device still holds only the old token. Without a grace period it would be locked
-- out and have to pair again. The previous token therefore stays valid until the new one is first
-- used, and for at most one day.
ALTER TABLE devices
    ADD COLUMN previous_token_hash bytea CHECK (previous_token_hash IS NULL OR octet_length(previous_token_hash) = 32),
    ADD COLUMN previous_token_expires_at timestamptz;
