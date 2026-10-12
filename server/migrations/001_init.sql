-- RelaySMS relay schema, version 1.
-- The relay stores public keys, hashed credentials, routing metadata and opaque ciphertext.
-- It never stores message plaintext, sender addresses, OTP codes or private keys.

CREATE TABLE devices (
    id                  uuid PRIMARY KEY,
    role                text        NOT NULL CHECK (role IN ('source', 'companion')),
    hpke_public_key     bytea       NOT NULL CHECK (octet_length(hpke_public_key) = 32),
    signing_public_key  bytea       NOT NULL CHECK (octet_length(signing_public_key) = 32),
    token_hash          bytea       NOT NULL CHECK (octet_length(token_hash) = 32),
    created_at          timestamptz NOT NULL,
    last_seen_at        timestamptz NOT NULL,
    revoked_at          timestamptz
);

CREATE TABLE pairing_sessions (
    id                      uuid PRIMARY KEY,
    companion_id            uuid        NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    source_id               uuid        REFERENCES devices (id) ON DELETE CASCADE,
    secret_hash             bytea       NOT NULL CHECK (octet_length(secret_hash) = 32),
    status                  text        NOT NULL CHECK (status IN ('pending', 'redeemed', 'completed', 'cancelled', 'expired')),
    failed_attempts         integer     NOT NULL DEFAULT 0,
    source_confirmed_at     timestamptz,
    companion_confirmed_at  timestamptz,
    created_at              timestamptz NOT NULL,
    expires_at              timestamptz NOT NULL,
    redeemed_at             timestamptz,
    completed_at            timestamptz
);
CREATE INDEX pairing_sessions_companion_idx ON pairing_sessions (companion_id);
CREATE INDEX pairing_sessions_expires_idx ON pairing_sessions (expires_at);

-- An authorised source <-> companion relationship. Each device has at most one peer.
CREATE TABLE links (
    id            uuid PRIMARY KEY,
    source_id     uuid        NOT NULL UNIQUE REFERENCES devices (id) ON DELETE CASCADE,
    companion_id  uuid        NOT NULL UNIQUE REFERENCES devices (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL
);

-- Temporary ciphertext queue. ciphertext is set to NULL when the recipient acknowledges; the
-- remaining row is a content-free tombstone that makes re-submission idempotent until it expires.
CREATE TABLE messages (
    sender_id     uuid        NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    message_id    uuid        NOT NULL,
    recipient_id  uuid        NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    ciphertext    bytea,
    signature     bytea       NOT NULL CHECK (octet_length(signature) = 64),
    size_bytes    integer     NOT NULL,
    created_at    timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    acked_at      timestamptz,
    PRIMARY KEY (sender_id, message_id)
);
CREATE INDEX messages_pending_idx ON messages (recipient_id, created_at) WHERE acked_at IS NULL;
CREATE INDEX messages_expires_idx ON messages (expires_at);
