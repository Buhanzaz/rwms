alter table kpi_palette
  add column completed_color varchar(7) not null default '#238636';

alter table kpi_palette
  add constraint ck_kpi_palette_completed_color
  check (completed_color ~ '^#[0-9A-Fa-f]{6}$');
