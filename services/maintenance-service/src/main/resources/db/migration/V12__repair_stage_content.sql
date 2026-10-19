ALTER TABLE public.estimate_plan_stage
  ADD COLUMN included_line_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN primary_line_id uuid,
  ADD COLUMN group_comment varchar(2000) NOT NULL DEFAULT '';

ALTER TABLE public.estimate_plan_stage
  ADD CONSTRAINT ck_estimate_plan_included_lines
    CHECK (jsonb_typeof(included_line_ids) = 'array');

ALTER TABLE public.repair_stage
  ADD COLUMN work_lines jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN material_lines jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN primary_line_id uuid,
  ADD COLUMN group_comment varchar(2000) NOT NULL DEFAULT '';

ALTER TABLE public.repair_stage
  ADD CONSTRAINT ck_repair_stage_work_lines
    CHECK (jsonb_typeof(work_lines) = 'array'),
  ADD CONSTRAINT ck_repair_stage_material_lines
    CHECK (jsonb_typeof(material_lines) = 'array');
