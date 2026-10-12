-- Defence in depth for databases that also expose an auto-generated HTTP API (for example
-- Supabase). With row level security enabled and no policies, no role other than the table owner
-- (the relay's own database user) can read or write these tables, even if this schema is ever
-- exposed through such an API by mistake. The relay itself is unaffected.
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE pairing_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE links ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE schema_migrations ENABLE ROW LEVEL SECURITY;
