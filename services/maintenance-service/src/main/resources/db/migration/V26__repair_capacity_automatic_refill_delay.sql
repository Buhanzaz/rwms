-- The one per-warehouse repair-capacity aggregate also owns the automatic-refill delay.
-- Existing rows receive the explicit default; no separate settings source or runtime fallback exists.
ALTER TABLE public.repair_capacity_settings
  ADD COLUMN automatic_refill_delay_minutes integer NOT NULL DEFAULT 5,
  ADD CONSTRAINT ck_repair_capacity_settings_refill_delay
    CHECK (automatic_refill_delay_minutes BETWEEN 1 AND 1440);

COMMENT ON COLUMN public.repair_capacity_settings.automatic_refill_delay_minutes IS
  'Minutes before a manually opened current logistics lane may be refilled automatically.';
