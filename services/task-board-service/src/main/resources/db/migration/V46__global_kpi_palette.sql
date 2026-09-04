CREATE TABLE public.kpi_settings (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  revision_marker uuid NOT NULL,
  palette_id uuid,
  CONSTRAINT uk_kpi_settings_palette UNIQUE (palette_id),
  CONSTRAINT fk_kpi_settings_palette
    FOREIGN KEY (palette_id) REFERENCES public.kpi_palette(id),
  CONSTRAINT ck_kpi_settings_singleton
    CHECK (id = '00000000-0000-0000-0000-000000000001'::uuid)
);
