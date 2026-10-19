-- Server-authoritative Driver Up daily-shift state machine and resumable inspection/closing state.

CREATE TABLE public.driver_shift_plan (
  id uuid NOT NULL, version bigint NOT NULL DEFAULT 0,
  source_shift_id uuid NOT NULL, source_plan_id uuid NOT NULL, source_plan_version bigint NOT NULL,
  request_fingerprint varchar(64) NOT NULL, warehouse_id uuid NOT NULL, driver_id uuid NOT NULL,
  driver_name varchar(256) NOT NULL, work_date date NOT NULL,
  vehicle_id uuid NOT NULL, vehicle_name varchar(256) NOT NULL,
  vehicle_registration_number varchar(64) NOT NULL, vehicle_type varchar(64),
  vehicle_manufacturer varchar(128), vehicle_model varchar(128),
  configuration_type varchar(32) NOT NULL, start_odometer bigint,
  trailer_id uuid, trailer_name varchar(256), trailer_registration_number varchar(64),
  trip_count integer NOT NULL, route_distance_meters bigint NOT NULL, frozen_shift_id uuid,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  CONSTRAINT driver_shift_plan_pkey PRIMARY KEY (id),
  CONSTRAINT uk_driver_shift_plan_source_shift UNIQUE (source_shift_id),
  CONSTRAINT uk_driver_shift_plan_driver_date UNIQUE (driver_id, work_date),
  CONSTRAINT ck_driver_shift_plan_version CHECK (version >= 0 AND source_plan_version >= 1),
  CONSTRAINT ck_driver_shift_plan_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_driver_shift_plan_configuration CHECK (configuration_type IN ('TRUCK','TRUCK_WITH_TRAILER','TRUCK_WITH_CRANE')),
  CONSTRAINT ck_driver_shift_plan_metrics CHECK (trip_count >= 0 AND route_distance_meters >= 0 AND (start_odometer IS NULL OR start_odometer >= 0)),
  CONSTRAINT ck_driver_shift_plan_trailer CHECK ((configuration_type='TRUCK_WITH_TRAILER')=(trailer_id IS NOT NULL AND trailer_name IS NOT NULL AND trailer_registration_number IS NOT NULL))
);
CREATE INDEX idx_driver_shift_plan_warehouse_date ON public.driver_shift_plan(warehouse_id,work_date);

CREATE TABLE public.driver_shift (
  id uuid NOT NULL, version bigint NOT NULL DEFAULT 0, plan_id uuid NOT NULL,
  driver_id uuid NOT NULL, driver_name varchar(256) NOT NULL, warehouse_id uuid NOT NULL,
  work_date date NOT NULL, time_zone varchar(64) NOT NULL, vehicle_id uuid NOT NULL,
  status varchar(40) NOT NULL, briefing_seen_at timestamptz,
  medical_confirmation_type varchar(40), medical_completed_at timestamptz,
  medical_client_completed_at timestamptz, medical_external_check_id uuid,
  medical_doctor_id uuid, medical_provider varchar(256), medical_checked_at timestamptz,
  vehicle_inspection_completed_at timestamptz, started_at timestamptz,
  closing_started_at timestamptz, returned_to_warehouse_at timestamptz,
  return_confirmation_type varchar(24), end_vehicle_condition varchar(32),
  end_odometer bigint, fuel_level_percent integer, closing_defect_id uuid,
  closing_report_completed_at timestamptz, closed_at timestamptz,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  CONSTRAINT driver_shift_pkey PRIMARY KEY (id),
  CONSTRAINT uk_driver_shift_driver_date UNIQUE (driver_id,work_date),
  CONSTRAINT fk_driver_shift_plan FOREIGN KEY (plan_id) REFERENCES public.driver_shift_plan(id),
  CONSTRAINT ck_driver_shift_version CHECK (version >= 0),
  CONSTRAINT ck_driver_shift_status CHECK (status IN ('DAILY_BRIEFING_REQUIRED','MEDICAL_CHECK_REQUIRED','VEHICLE_INSPECTION_REQUIRED','READY_TO_START','SHIFT_ACTIVE','SHIFT_CLOSING','RETURN_TO_WAREHOUSE_REQUIRED','END_VEHICLE_CHECK_REQUIRED','SHIFT_READY_TO_CLOSE','SHIFT_CLOSED')),
  CONSTRAINT ck_driver_shift_medical_type CHECK (medical_confirmation_type IS NULL OR medical_confirmation_type IN ('SELF_CONFIRMATION_TEST','EXTERNAL_MEDICAL_SYSTEM')),
  CONSTRAINT ck_driver_shift_return_type CHECK (return_confirmation_type IS NULL OR return_confirmation_type IN ('MANUAL','GEOFENCE')),
  CONSTRAINT ck_driver_shift_condition CHECK (end_vehicle_condition IS NULL OR end_vehicle_condition IN ('NO_NEW_DEFECTS','DEFECT_REPORTED')),
  CONSTRAINT ck_driver_shift_closing_values CHECK ((end_odometer IS NULL OR end_odometer >= 0) AND (fuel_level_percent IS NULL OR fuel_level_percent BETWEEN 0 AND 100))
);
CREATE INDEX idx_driver_shift_warehouse_date_status ON public.driver_shift(warehouse_id,work_date,status);
ALTER TABLE public.driver_shift_plan ADD CONSTRAINT fk_driver_shift_plan_frozen_shift FOREIGN KEY (frozen_shift_id) REFERENCES public.driver_shift(id);

CREATE TABLE public.vehicle_inspection_template (
  id uuid NOT NULL, configuration_type varchar(32) NOT NULL, template_version bigint NOT NULL,
  active boolean NOT NULL, created_at timestamptz NOT NULL,
  CONSTRAINT vehicle_inspection_template_pkey PRIMARY KEY (id),
  CONSTRAINT uk_vehicle_inspection_template_version UNIQUE (configuration_type,template_version),
  CONSTRAINT ck_vehicle_inspection_template_type CHECK (configuration_type IN ('TRUCK','TRUCK_WITH_TRAILER','TRUCK_WITH_CRANE')),
  CONSTRAINT ck_vehicle_inspection_template_version CHECK (template_version >= 1)
);
CREATE UNIQUE INDEX uk_vehicle_inspection_template_active ON public.vehicle_inspection_template(configuration_type) WHERE active;

CREATE TABLE public.vehicle_inspection_template_item (
  id uuid NOT NULL, template_id uuid NOT NULL, item_code varchar(96) NOT NULL,
  section_name varchar(128) NOT NULL, item_label varchar(256) NOT NULL,
  required boolean NOT NULL, sort_order integer NOT NULL,
  CONSTRAINT vehicle_inspection_template_item_pkey PRIMARY KEY (id),
  CONSTRAINT fk_vehicle_inspection_template_item_template FOREIGN KEY (template_id) REFERENCES public.vehicle_inspection_template(id),
  CONSTRAINT uk_vehicle_inspection_template_item_code UNIQUE (template_id,item_code),
  CONSTRAINT uk_vehicle_inspection_template_item_order UNIQUE (template_id,sort_order),
  CONSTRAINT ck_vehicle_inspection_template_item_order CHECK (sort_order >= 0)
);

CREATE TABLE public.vehicle_inspection (
  id uuid NOT NULL, version bigint NOT NULL DEFAULT 0, shift_id uuid NOT NULL,
  vehicle_id uuid NOT NULL, configuration_type varchar(32) NOT NULL,
  template_version bigint NOT NULL, created_at timestamptz NOT NULL, completed_at timestamptz,
  CONSTRAINT vehicle_inspection_pkey PRIMARY KEY (id),
  CONSTRAINT uk_vehicle_inspection_shift UNIQUE (shift_id),
  CONSTRAINT fk_vehicle_inspection_shift FOREIGN KEY (shift_id) REFERENCES public.driver_shift(id),
  CONSTRAINT ck_vehicle_inspection_version CHECK (version >= 0 AND template_version >= 1),
  CONSTRAINT ck_vehicle_inspection_configuration CHECK (configuration_type IN ('TRUCK','TRUCK_WITH_TRAILER','TRUCK_WITH_CRANE'))
);

CREATE TABLE public.vehicle_inspection_item_result (
  id uuid NOT NULL, version bigint NOT NULL DEFAULT 0, inspection_id uuid NOT NULL,
  shift_id uuid NOT NULL, template_item_code varchar(96) NOT NULL,
  section_name varchar(128) NOT NULL, item_label varchar(256) NOT NULL,
  required boolean NOT NULL, sort_order integer NOT NULL, result_state varchar(24) NOT NULL,
  defect_id uuid, checked_at timestamptz,
  CONSTRAINT vehicle_inspection_item_result_pkey PRIMARY KEY (id),
  CONSTRAINT fk_vehicle_inspection_item_inspection FOREIGN KEY (inspection_id) REFERENCES public.vehicle_inspection(id),
  CONSTRAINT fk_vehicle_inspection_item_shift FOREIGN KEY (shift_id) REFERENCES public.driver_shift(id),
  CONSTRAINT uk_vehicle_inspection_item_code UNIQUE (inspection_id,template_item_code),
  CONSTRAINT ck_vehicle_inspection_item_version CHECK (version >= 0 AND sort_order >= 0),
  CONSTRAINT ck_vehicle_inspection_item_state CHECK (result_state IN ('NOT_CHECKED','OK','DEFECT')),
  CONSTRAINT ck_vehicle_inspection_item_defect CHECK ((result_state='DEFECT')=(defect_id IS NOT NULL))
);
CREATE INDEX idx_vehicle_inspection_item_shift ON public.vehicle_inspection_item_result(shift_id,sort_order);

CREATE TABLE public.vehicle_defect (
  id uuid NOT NULL, version bigint NOT NULL DEFAULT 0, shift_id uuid NOT NULL,
  driver_id uuid NOT NULL, vehicle_id uuid NOT NULL, work_date date NOT NULL,
  inspection_item_id uuid, description varchar(2000) NOT NULL,
  severity varchar(16) NOT NULL, status varchar(16) NOT NULL,
  created_at timestamptz NOT NULL, resolved_at timestamptz,
  CONSTRAINT vehicle_defect_pkey PRIMARY KEY (id),
  CONSTRAINT fk_vehicle_defect_shift FOREIGN KEY (shift_id) REFERENCES public.driver_shift(id),
  CONSTRAINT fk_vehicle_defect_inspection_item FOREIGN KEY (inspection_item_id) REFERENCES public.vehicle_inspection_item_result(id),
  CONSTRAINT ck_vehicle_defect_version CHECK (version >= 0),
  CONSTRAINT ck_vehicle_defect_description CHECK (btrim(description)<>''),
  CONSTRAINT ck_vehicle_defect_severity CHECK (severity IN ('INFO','BLOCKING')),
  CONSTRAINT ck_vehicle_defect_status CHECK (status IN ('OPEN','RESOLVED')),
  CONSTRAINT ck_vehicle_defect_resolved CHECK ((status='RESOLVED')=(resolved_at IS NOT NULL))
);
CREATE INDEX idx_vehicle_defect_shift ON public.vehicle_defect(shift_id,created_at);
CREATE INDEX idx_vehicle_defect_vehicle_status ON public.vehicle_defect(vehicle_id,status);
ALTER TABLE public.vehicle_inspection_item_result ADD CONSTRAINT fk_vehicle_inspection_item_defect FOREIGN KEY (defect_id) REFERENCES public.vehicle_defect(id);
ALTER TABLE public.driver_shift ADD CONSTRAINT fk_driver_shift_closing_defect FOREIGN KEY (closing_defect_id) REFERENCES public.vehicle_defect(id);

CREATE TABLE public.driver_shift_photo (
  id uuid NOT NULL, version bigint NOT NULL DEFAULT 0, shift_id uuid NOT NULL,
  driver_id uuid NOT NULL, warehouse_id uuid NOT NULL, client_reference_id uuid NOT NULL,
  evidence_id uuid NOT NULL, photo_role varchar(32) NOT NULL, defect_id uuid,
  inspection_item_id uuid, state varchar(24) NOT NULL, media_id uuid,
  media_generation bigint, captured_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL,
  content_type varchar(128) NOT NULL, size_bytes bigint NOT NULL, sha256 varchar(64) NOT NULL,
  review_reason varchar(512),
  CONSTRAINT driver_shift_photo_pkey PRIMARY KEY (id),
  CONSTRAINT uk_driver_shift_photo_client_reference UNIQUE (shift_id,client_reference_id),
  CONSTRAINT uk_driver_shift_photo_evidence UNIQUE (evidence_id),
  CONSTRAINT fk_driver_shift_photo_shift FOREIGN KEY (shift_id) REFERENCES public.driver_shift(id),
  CONSTRAINT fk_driver_shift_photo_defect FOREIGN KEY (defect_id) REFERENCES public.vehicle_defect(id),
  CONSTRAINT fk_driver_shift_photo_inspection_item FOREIGN KEY (inspection_item_id) REFERENCES public.vehicle_inspection_item_result(id),
  CONSTRAINT ck_driver_shift_photo_version CHECK (version >= 0),
  CONSTRAINT ck_driver_shift_photo_role CHECK (photo_role IN ('INSPECTION_DEFECT','END_SHIFT_DEFECT','VEHICLE_OVERVIEW')),
  CONSTRAINT ck_driver_shift_photo_state CHECK (state IN ('RESERVED','PROCESSING','READY','REVIEW_REQUIRED')),
  CONSTRAINT ck_driver_shift_photo_media CHECK ((state IN ('RESERVED','PROCESSING') AND media_generation IS NULL) OR state IN ('READY','REVIEW_REQUIRED')),
  CONSTRAINT ck_driver_shift_photo_upload CHECK (content_type='image/jpeg' AND size_bytes BETWEEN 1 AND 15728640 AND sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_driver_shift_photo_generation CHECK (media_generation IS NULL OR media_generation >= 1)
);
CREATE INDEX idx_driver_shift_photo_shift_state ON public.driver_shift_photo(shift_id,state);

CREATE TABLE public.driver_shift_command_receipt (
  operation_id uuid NOT NULL, idempotency_key uuid NOT NULL, shift_id uuid NOT NULL,
  driver_id uuid NOT NULL, command_type varchar(48) NOT NULL, request_sha256 char(64) NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT driver_shift_command_receipt_pkey PRIMARY KEY (operation_id),
  CONSTRAINT uk_driver_shift_command_key UNIQUE (driver_id,idempotency_key),
  CONSTRAINT fk_driver_shift_command_shift FOREIGN KEY (shift_id) REFERENCES public.driver_shift(id),
  CONSTRAINT ck_driver_shift_command_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.driver_shift_media_event_inbox (
  event_id uuid NOT NULL, media_id uuid NOT NULL, aggregate_version bigint NOT NULL,
  event_type varchar(96) NOT NULL, shift_id uuid NOT NULL, evidence_id uuid NOT NULL,
  warehouse_id uuid NOT NULL, actor_worker_id uuid NOT NULL, media_generation bigint,
  body_sha256 char(64) NOT NULL, status varchar(16) NOT NULL, failure_code varchar(64),
  received_at timestamptz NOT NULL, processed_at timestamptz,
  CONSTRAINT driver_shift_media_event_inbox_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_driver_shift_media_event_stream UNIQUE (media_id,aggregate_version),
  CONSTRAINT ck_driver_shift_media_event_version CHECK (aggregate_version >= 1),
  CONSTRAINT ck_driver_shift_media_event_type CHECK (event_type IN ('media.media.uploaded.v1','media.media.ready.v1','media.media.failed.v1')),
  CONSTRAINT ck_driver_shift_media_event_status CHECK (status IN ('PENDING','APPLIED','IGNORED','REJECTED')),
  CONSTRAINT ck_driver_shift_media_event_hash CHECK (body_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_driver_shift_media_event_pending ON public.driver_shift_media_event_inbox(received_at,event_id) WHERE status='PENDING';

-- Templates are DB-owned configuration; each inspection copies these rows before the driver answers.
INSERT INTO public.vehicle_inspection_template(id,configuration_type,template_version,active,created_at) VALUES
 ('10000000-0000-0000-0000-000000000001','TRUCK',1,true,clock_timestamp()),
 ('10000000-0000-0000-0000-000000000002','TRUCK_WITH_TRAILER',1,true,clock_timestamp()),
 ('10000000-0000-0000-0000-000000000003','TRUCK_WITH_CRANE',1,true,clock_timestamp());

INSERT INTO public.vehicle_inspection_template_item(id,template_id,item_code,section_name,item_label,required,sort_order)
SELECT ('20000000-0000-0000-0000-'||lpad(row_number() over()::text,12,'0'))::uuid, t.id, v.code, v.section_name, v.label, true, v.position
FROM public.vehicle_inspection_template t
JOIN (VALUES
 ('TRUCK','TRUCK_BRAKES','Грузовик','Тормозная система',1),('TRUCK','TRUCK_TIRES','Грузовик','Шины и колёса',2),('TRUCK','TRUCK_HEADLIGHTS','Грузовик','Фары',3),('TRUCK','TRUCK_BRAKE_LIGHTS','Грузовик','Стоп-сигналы',4),('TRUCK','TRUCK_TURN_SIGNALS','Грузовик','Поворотники',5),('TRUCK','TRUCK_MIRRORS','Грузовик','Зеркала',6),('TRUCK','TRUCK_WIPERS','Грузовик','Стеклоочистители',7),('TRUCK','TRUCK_STEERING','Грузовик','Рулевое управление',8),('TRUCK','TRUCK_LEAKS','Грузовик','Утечки технических жидкостей',9),('TRUCK','TRUCK_DAMAGE','Грузовик','Видимые повреждения',10),
 ('TRUCK_WITH_TRAILER','TRUCK_BRAKES','Грузовик','Тормозная система',1),('TRUCK_WITH_TRAILER','TRUCK_TIRES','Грузовик','Шины и колёса',2),('TRUCK_WITH_TRAILER','TRUCK_HEADLIGHTS','Грузовик','Фары',3),('TRUCK_WITH_TRAILER','TRUCK_BRAKE_LIGHTS','Грузовик','Стоп-сигналы',4),('TRUCK_WITH_TRAILER','TRUCK_TURN_SIGNALS','Грузовик','Поворотники',5),('TRUCK_WITH_TRAILER','TRUCK_MIRRORS','Грузовик','Зеркала',6),('TRUCK_WITH_TRAILER','TRUCK_WIPERS','Грузовик','Стеклоочистители',7),('TRUCK_WITH_TRAILER','TRUCK_STEERING','Грузовик','Рулевое управление',8),('TRUCK_WITH_TRAILER','TRUCK_LEAKS','Грузовик','Утечки технических жидкостей',9),('TRUCK_WITH_TRAILER','TRUCK_DAMAGE','Грузовик','Видимые повреждения',10),
 ('TRUCK_WITH_TRAILER','TRAILER_COUPLING','Прицеп','Сцепное устройство',11),('TRUCK_WITH_TRAILER','TRAILER_BRAKES','Прицеп','Тормозная система',12),('TRUCK_WITH_TRAILER','TRAILER_TIRES','Прицеп','Шины и колёса',13),('TRUCK_WITH_TRAILER','TRAILER_ELECTRICAL','Прицеп','Электрика',14),('TRUCK_WITH_TRAILER','TRAILER_LIGHTS','Прицеп','Фонари',15),('TRUCK_WITH_TRAILER','TRAILER_SUPPORTS','Прицеп','Опоры и механизмы',16),('TRUCK_WITH_TRAILER','TRAILER_DAMAGE','Прицеп','Видимые повреждения',17),
 ('TRUCK_WITH_TRAILER','TRAIN_CONNECTION','Автопоезд','Соединение тягача и прицепа',18),('TRUCK_WITH_TRAILER','TRAIN_AIR_LINES','Автопоезд','Пневмолинии',19),('TRUCK_WITH_TRAILER','TRAIN_ELECTRICAL','Автопоезд','Электрическое соединение',20),('TRUCK_WITH_TRAILER','TRAIN_MARKER_LIGHTS','Автопоезд','Габаритные огни',21),('TRUCK_WITH_TRAILER','TRAIN_PLATES','Автопоезд','Номерные знаки',22),('TRUCK_WITH_TRAILER','TRAIN_EQUIPMENT','Автопоезд','Надёжность соединения оборудования',23),
 ('TRUCK_WITH_CRANE','TRUCK_BRAKES','Грузовик','Тормозная система',1),('TRUCK_WITH_CRANE','TRUCK_TIRES','Грузовик','Шины и колёса',2),('TRUCK_WITH_CRANE','TRUCK_HEADLIGHTS','Грузовик','Фары',3),('TRUCK_WITH_CRANE','TRUCK_BRAKE_LIGHTS','Грузовик','Стоп-сигналы',4),('TRUCK_WITH_CRANE','TRUCK_TURN_SIGNALS','Грузовик','Поворотники',5),('TRUCK_WITH_CRANE','TRUCK_MIRRORS','Грузовик','Зеркала',6),('TRUCK_WITH_CRANE','TRUCK_WIPERS','Грузовик','Стеклоочистители',7),('TRUCK_WITH_CRANE','TRUCK_STEERING','Грузовик','Рулевое управление',8),('TRUCK_WITH_CRANE','TRUCK_LEAKS','Грузовик','Утечки технических жидкостей',9),('TRUCK_WITH_CRANE','TRUCK_DAMAGE','Грузовик','Видимые повреждения',10),('TRUCK_WITH_CRANE','CRANE_EQUIPMENT','Кран','Надёжность соединения оборудования',11)
) AS v(configuration_type,code,section_name,label,position) ON v.configuration_type=t.configuration_type;

ALTER TABLE public.event_stream_head DROP CONSTRAINT ck_event_stream_head_type;
ALTER TABLE public.event_stream_head ADD CONSTRAINT ck_event_stream_head_type CHECK (aggregate_type IN ('WORKER_CLASS','WORKER','WORKER_GROUP','WORK_QUEUE','QUEUE_USAGE_REFERENCE','BOARD_TASK','QUEUE_ENTRY','TASK_BOARD_ENTRY_OWNER_PROOF','TASK_EVIDENCE','GROUP_KPI_DAY','DRIVER_SHIFT_OWNER_PROOF'));
ALTER TABLE public.domain_event DROP CONSTRAINT ck_domain_event_type;
ALTER TABLE public.domain_event ADD CONSTRAINT ck_domain_event_type CHECK (aggregate_type IN ('WORKER_CLASS','WORKER','WORKER_GROUP','WORK_QUEUE','QUEUE_USAGE_REFERENCE','BOARD_TASK','QUEUE_ENTRY','TASK_BOARD_ENTRY_OWNER_PROOF','TASK_EVIDENCE','GROUP_KPI_DAY','DRIVER_SHIFT_OWNER_PROOF'));
ALTER TABLE public.projection_checkpoint DROP CONSTRAINT ck_projection_checkpoint_type;
ALTER TABLE public.projection_checkpoint ADD CONSTRAINT ck_projection_checkpoint_type CHECK (aggregate_type IN ('WORKER_CLASS','WORKER','WORKER_GROUP','WORK_QUEUE','QUEUE_USAGE_REFERENCE','BOARD_TASK','QUEUE_ENTRY','TASK_BOARD_ENTRY_OWNER_PROOF','TASK_EVIDENCE','GROUP_KPI_DAY','DRIVER_SHIFT_OWNER_PROOF'));
ALTER TABLE public.outbox_event DROP CONSTRAINT ck_outbox_event_topic;
ALTER TABLE public.outbox_event ADD CONSTRAINT ck_outbox_event_topic CHECK ((aggregate_type IN ('WORKER_CLASS','WORKER','WORKER_GROUP','WORK_QUEUE','QUEUE_USAGE_REFERENCE','BOARD_TASK','QUEUE_ENTRY','GROUP_KPI_DAY') AND topic='rwms.task-board.'||replace(lower(aggregate_type),'_','-')||'.v1') OR (aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF' AND topic='rwms.task-board.entry-owner-proof.v1') OR (aggregate_type='TASK_EVIDENCE' AND topic='rwms.task-board.task-evidence.v1') OR (aggregate_type='DRIVER_SHIFT_OWNER_PROOF' AND topic='rwms.task-board.driver-shift-owner-proof.v1'));
