-- Catalog button colors are versioned with the catalog node so panel and mobile clients read the
-- same presentation hint from the maintenance-service source of truth.

ALTER TABLE public.catalog_node
  ADD COLUMN display_color varchar(7);

ALTER TABLE public.catalog_node
  ADD CONSTRAINT ck_catalog_node_display_color
    CHECK (display_color IS NULL OR display_color ~ '^#[0-9A-F]{6}$');
