-- The bakery's time zone (#12): slot times and pickup dates are read in it instead of the server's zone.
-- IANA id such as 'Europe/London'; validated by the application (Postgres has no zone-id type).
-- Existing rows get UTC; the Owner sets the real zone through PUT /api/owner/profile.
ALTER TABLE owners ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC';
