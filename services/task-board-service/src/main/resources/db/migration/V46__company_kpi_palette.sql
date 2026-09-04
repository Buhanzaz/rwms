CREATE TABLE public.company_kpi_settings (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  revision_marker uuid NOT NULL,
  palette_id uuid,
  CONSTRAINT uk_company_kpi_settings_palette UNIQUE (palette_id),
  CONSTRAINT fk_company_kpi_settings_palette
    FOREIGN KEY (palette_id) REFERENCES public.kpi_palette(id)
);
