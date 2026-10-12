-- Unique table names. The relay lives in its own schema, but if a connection ever lost its
-- search path, a query for "devices" or "messages" could resolve to another application's table
-- of the same name in a shared database. With these names such a query can only fail.
ALTER TABLE devices RENAME TO relay_devices;
ALTER TABLE pairing_sessions RENAME TO relay_pairing_sessions;
ALTER TABLE links RENAME TO relay_links;
ALTER TABLE messages RENAME TO relay_messages;
