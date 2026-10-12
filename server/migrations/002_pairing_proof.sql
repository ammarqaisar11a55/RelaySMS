-- Opaque proof supplied by the source when it redeems a pairing code. It is an HMAC keyed with a
-- secret that only the two phones know (it travels in the QR code and is never sent to the relay),
-- so the relay can store and forward the proof but cannot forge one.
ALTER TABLE pairing_sessions
    ADD COLUMN source_proof bytea CHECK (source_proof IS NULL OR octet_length(source_proof) = 32);
