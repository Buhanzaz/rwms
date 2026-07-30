ALTER TABLE public.maintenance_repair
  ADD COLUMN priority integer NOT NULL DEFAULT 3,
  ADD CONSTRAINT ck_maintenance_repair_priority CHECK (priority BETWEEN 1 AND 5);
