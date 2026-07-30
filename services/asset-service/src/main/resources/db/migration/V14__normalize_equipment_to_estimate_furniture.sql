-- The estimate/repair catalog is the canonical equipment dictionary. The V5
-- old-panel transfer predates that link and created nine broad legacy items.
-- Preserve their immutable movement history, but move every current balance to
-- the eleven active furniture items used by the estimate catalog.

CREATE TEMPORARY TABLE equipment_normalization_event (
  event_id uuid PRIMARY KEY,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id uuid NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(160) NOT NULL,
  topic varchar(192) NOT NULL,
  payload jsonb NOT NULL,
  snapshot jsonb NOT NULL,
  recorded_at timestamptz NOT NULL,
  initialize_stream boolean NOT NULL,
  UNIQUE (aggregate_type, aggregate_id, aggregate_version)
) ON COMMIT DROP;

CREATE TEMPORARY TABLE canonical_equipment_seed (
  sequence_no integer PRIMARY KEY,
  fallback_id uuid NOT NULL UNIQUE,
  code varchar(64) NOT NULL UNIQUE,
  name varchar(255) NOT NULL UNIQUE
) ON COMMIT DROP;

INSERT INTO canonical_equipment_seed(sequence_no, fallback_id, code, name)
VALUES
  (1, '52140000-0000-4000-8000-000000000001', 'CONVECTOR_1_5KW_NO_SOCKET',
    'Конвектор 1,5 кВ без доп розетки'),
  (2, '52140000-0000-4000-8000-000000000002', 'METAL_BUNK_BED',
    'Кровать двухъярусная металлическая'),
  (3, '52140000-0000-4000-8000-000000000003', 'METAL_BENCH',
    'Лавка металлическая'),
  (4, '52140000-0000-4000-8000-000000000004', 'DINING_TABLE',
    'Стол обеденный'),
  (5, '52140000-0000-4000-8000-000000000005', 'LDSP_OFFICE_TABLE_1200',
    'Стол офисный ЛДСП 1200мм'),
  (6, '52140000-0000-4000-8000-000000000006', 'LDSP_OFFICE_TABLE_900',
    'Стол офисный ЛДСП 900мм'),
  (7, '52140000-0000-4000-8000-000000000007', 'OFFICE_CHAIR',
    'Стул офисный'),
  (8, '52140000-0000-4000-8000-000000000008', 'BEDSIDE_CABINET',
    'Тумба прикроватная'),
  (9, '52140000-0000-4000-8000-000000000009', 'LDSP_DRAWER_CABINET',
    'Тумба с ящиками ЛДСП'),
  (10, '52140000-0000-4000-8000-000000000010', 'LDSP_DOCUMENT_CABINET',
    'Шкаф для бумаг ЛДСП'),
  (11, '52140000-0000-4000-8000-000000000011', 'LDSP_OFFICE_RACK',
    'Шкаф офисный ЛДСП стеллаж');

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM canonical_equipment_seed AS seed
    JOIN public.equipment_catalog_item AS item ON item.code = seed.code
    WHERE item.name <> seed.name
       OR item.category <> 'FURNITURE'
  ) THEN
    RAISE EXCEPTION
      'canonical estimate furniture identity conflicts with the asset catalog';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.equipment_balance AS balance
    JOIN public.equipment_catalog_item AS item ON item.id = balance.equipment_id
    WHERE balance.quantity > 0
      AND item.code NOT IN (
        SELECT code FROM canonical_equipment_seed
      )
      AND item.code NOT IN (
        'TABLE',
        'OFFICE_TABLE',
        'BENCH',
        'CHAIR',
        'BED',
        'BUNK_BED',
        'WARDROBE',
        'CONVECTOR',
        'AIR_CONDITIONER'
      )
  ) THEN
    RAISE EXCEPTION
      'non-canonical equipment with a positive balance requires explicit classification';
  END IF;
END $$;

CREATE TEMPORARY TABLE created_canonical_equipment (
  id uuid PRIMARY KEY
) ON COMMIT DROP;

WITH inserted AS (
  INSERT INTO public.equipment_catalog_item(
    id,
    version,
    code,
    name,
    category,
    active,
    comment,
    created_at,
    updated_at)
  SELECT seed.fallback_id,
         0,
         seed.code,
         seed.name,
         'FURNITURE',
         true,
         null,
         clock_timestamp(),
         clock_timestamp()
  FROM canonical_equipment_seed AS seed
  WHERE NOT EXISTS (
    SELECT 1
    FROM public.equipment_catalog_item AS item
    WHERE item.code = seed.code
  )
  RETURNING id
)
INSERT INTO created_canonical_equipment(id)
SELECT id
FROM inserted;

INSERT INTO equipment_normalization_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  topic,
  payload,
  snapshot,
  recorded_at,
  initialize_stream)
SELECT gen_random_uuid(),
       'EQUIPMENT_CATALOG',
       item.id,
       item.version,
       'asset.equipment-catalog.created.v1',
       'rwms.asset.equipment-catalog.v1',
       jsonb_build_object(
         'equipmentId', item.id::text,
         'code', item.code,
         'category', item.category,
         'active', item.active),
       jsonb_build_object(
         'equipmentId', item.id::text,
         'version', item.version,
         'code', item.code,
         'name', item.name,
         'category', item.category,
         'active', item.active,
         'comment', item.comment),
       item.created_at,
       true
FROM created_canonical_equipment AS created
JOIN public.equipment_catalog_item AS item ON item.id = created.id;

CREATE TEMPORARY TABLE canonical_equipment (
  id uuid PRIMARY KEY,
  code varchar(64) NOT NULL UNIQUE,
  name varchar(255) NOT NULL
) ON COMMIT DROP;

INSERT INTO canonical_equipment(id, code, name)
SELECT item.id, item.code, item.name
FROM public.equipment_catalog_item AS item
JOIN canonical_equipment_seed AS seed ON seed.code = item.code;

DO $$
BEGIN
  IF (SELECT count(*) FROM canonical_equipment) <> 11 THEN
    RAISE EXCEPTION 'expected eleven canonical estimate furniture items';
  END IF;
END $$;

-- A disabled canonical item is reactivated before balances are assigned to it.
CREATE TEMPORARY TABLE reactivated_canonical_equipment (
  id uuid PRIMARY KEY,
  prior_version bigint NOT NULL,
  changed_at timestamptz NOT NULL
) ON COMMIT DROP;

INSERT INTO reactivated_canonical_equipment(id, prior_version, changed_at)
SELECT item.id, item.version, clock_timestamp()
FROM public.equipment_catalog_item AS item
JOIN canonical_equipment AS canonical ON canonical.id = item.id
WHERE NOT item.active;

UPDATE public.equipment_catalog_item AS item
SET active = true,
    version = item.version + 1,
    updated_at = changed.changed_at
FROM reactivated_canonical_equipment AS changed
WHERE item.id = changed.id
  AND item.version = changed.prior_version;

INSERT INTO equipment_normalization_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  topic,
  payload,
  snapshot,
  recorded_at,
  initialize_stream)
SELECT gen_random_uuid(),
       'EQUIPMENT_CATALOG',
       item.id,
       item.version,
       'asset.equipment-catalog.changed.v1',
       'rwms.asset.equipment-catalog.v1',
       jsonb_build_object(
         'equipmentId', item.id::text,
         'code', item.code,
         'category', item.category,
         'active', item.active),
       jsonb_build_object(
         'equipmentId', item.id::text,
         'version', item.version,
         'code', item.code,
         'name', item.name,
         'category', item.category,
         'active', item.active,
         'comment', item.comment),
       changed.changed_at,
       false
FROM reactivated_canonical_equipment AS changed
JOIN public.equipment_catalog_item AS item ON item.id = changed.id;

-- Active reservations still point at broad legacy identities. They cannot be
-- guessed into one of the split office-table/wardrobe variants, so release
-- them before the physical stock is deterministically reclassified. Historical
-- released/executed evidence and movement ledger rows remain untouched.
CREATE TEMPORARY TABLE released_legacy_hold (
  id uuid PRIMARY KEY,
  prior_version bigint NOT NULL,
  released_at timestamptz NOT NULL
) ON COMMIT DROP;

INSERT INTO released_legacy_hold(id, prior_version, released_at)
SELECT hold.id, hold.version, clock_timestamp()
FROM public.equipment_allocation_hold AS hold
JOIN public.equipment_catalog_item AS item ON item.id = hold.equipment_id
LEFT JOIN canonical_equipment AS canonical ON canonical.id = item.id
WHERE canonical.id IS NULL
  AND hold.state = 'ACTIVE';

UPDATE public.equipment_allocation_hold AS hold
SET version = hold.version + 1,
    state = 'RELEASED',
    released_at = changed.released_at,
    updated_at = changed.released_at
FROM released_legacy_hold AS changed
WHERE hold.id = changed.id
  AND hold.version = changed.prior_version
  AND hold.state = 'ACTIVE';

INSERT INTO equipment_normalization_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  topic,
  payload,
  snapshot,
  recorded_at,
  initialize_stream)
SELECT gen_random_uuid(),
       'EQUIPMENT_ALLOCATION_HOLD',
       hold.id,
       hold.version,
       'asset.equipment-allocation-hold.released.v1',
       'rwms.asset.equipment-allocation-hold.v1',
       jsonb_build_object(
         'holdId', hold.id::text,
         'equipmentId', hold.equipment_id::text,
         'warehouseId', hold.warehouse_id::text,
         'quantity', hold.quantity,
         'state', hold.state),
       jsonb_build_object(
         'holdId', hold.id::text,
         'version', hold.version,
         'equipmentId', hold.equipment_id::text,
         'warehouseId', hold.warehouse_id::text,
         'ownerType', hold.owner_type,
         'ownerId', hold.owner_id,
         'sourceBalanceId', hold.source_balance_id::text,
         'quantity', hold.quantity,
         'state', hold.state,
         'expiresAt', hold.expires_at,
         'committedAt', hold.committed_at,
         'executedAt', hold.executed_at),
       changed.released_at,
       false
FROM released_legacy_hold AS changed
JOIN public.equipment_allocation_hold AS hold ON hold.id = changed.id;

UPDATE public.order_equipment_reservation AS reservation
SET version = reservation.version + 1,
    state = 'RELEASED',
    released_at = clock_timestamp(),
    updated_at = clock_timestamp()
FROM public.equipment_catalog_item AS item
LEFT JOIN canonical_equipment AS canonical ON canonical.id = item.id
WHERE reservation.equipment_id = item.id
  AND canonical.id IS NULL
  AND reservation.state = 'ACTIVE';

-- One source row may produce one or two canonical rows. Odd split quantities
-- alternate deterministically, which yields 32/31 office tables and 28/28
-- wardrobes for the transferred demo data while keeping every even cabin row
-- split evenly.
CREATE TEMPORARY TABLE equipment_reclassification_allocation (
  source_balance_id uuid NOT NULL,
  target_equipment_id uuid NOT NULL,
  quantity bigint NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (source_balance_id, target_equipment_id)
) ON COMMIT DROP;

INSERT INTO equipment_reclassification_allocation(
  source_balance_id,
  target_equipment_id,
  quantity)
SELECT balance.id,
       target.id,
       balance.quantity
FROM public.equipment_balance AS balance
JOIN public.equipment_catalog_item AS source ON source.id = balance.equipment_id
JOIN canonical_equipment AS target
  ON target.code = CASE source.code
    WHEN 'TABLE' THEN 'DINING_TABLE'
    WHEN 'BENCH' THEN 'METAL_BENCH'
    WHEN 'CHAIR' THEN 'OFFICE_CHAIR'
    WHEN 'BED' THEN 'METAL_BUNK_BED'
    WHEN 'BUNK_BED' THEN 'METAL_BUNK_BED'
    WHEN 'CONVECTOR' THEN 'CONVECTOR_1_5KW_NO_SOCKET'
  END
WHERE source.code IN (
    'TABLE', 'BENCH', 'CHAIR', 'BED', 'BUNK_BED', 'CONVECTOR')
  AND balance.quantity > 0;

WITH odd_source AS (
  SELECT balance.id,
         source.code,
         row_number() OVER (
           PARTITION BY source.code
           ORDER BY
             balance.warehouse_id,
             balance.rental_item_id NULLS FIRST,
             balance.location_kind,
             balance.id) AS odd_sequence
  FROM public.equipment_balance AS balance
  JOIN public.equipment_catalog_item AS source ON source.id = balance.equipment_id
  WHERE source.code IN ('OFFICE_TABLE', 'WARDROBE')
    AND balance.quantity > 0
    AND mod(balance.quantity, 2) = 1
),
split_allocation AS (
  SELECT balance.id AS source_balance_id,
         target.id AS target_equipment_id,
         balance.quantity / 2
           + CASE
               WHEN mod(balance.quantity, 2) = 1
                 AND mod(odd.odd_sequence, 2) = 1
                 AND target.code IN (
                   'LDSP_OFFICE_TABLE_1200', 'LDSP_DOCUMENT_CABINET')
               THEN 1
               WHEN mod(balance.quantity, 2) = 1
                 AND mod(odd.odd_sequence, 2) = 0
                 AND target.code IN (
                   'LDSP_OFFICE_TABLE_900', 'LDSP_OFFICE_RACK')
               THEN 1
               ELSE 0
             END AS quantity
  FROM public.equipment_balance AS balance
  JOIN public.equipment_catalog_item AS source ON source.id = balance.equipment_id
  JOIN canonical_equipment AS target
    ON (source.code = 'OFFICE_TABLE'
          AND target.code IN (
            'LDSP_OFFICE_TABLE_1200', 'LDSP_OFFICE_TABLE_900'))
      OR (source.code = 'WARDROBE'
          AND target.code IN (
            'LDSP_DOCUMENT_CABINET', 'LDSP_OFFICE_RACK'))
  LEFT JOIN odd_source AS odd ON odd.id = balance.id
  WHERE source.code IN ('OFFICE_TABLE', 'WARDROBE')
    AND balance.quantity > 0
)
INSERT INTO equipment_reclassification_allocation(
  source_balance_id,
  target_equipment_id,
  quantity)
SELECT source_balance_id, target_equipment_id, quantity
FROM split_allocation
WHERE quantity > 0;

CREATE TEMPORARY TABLE equipment_reclassification_target_delta (
  target_equipment_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  rental_item_id uuid,
  location_kind varchar(32) NOT NULL,
  quantity bigint NOT NULL CHECK (quantity > 0)
) ON COMMIT DROP;

INSERT INTO equipment_reclassification_target_delta(
  target_equipment_id,
  warehouse_id,
  rental_item_id,
  location_kind,
  quantity)
SELECT allocation.target_equipment_id,
       source.warehouse_id,
       source.rental_item_id,
       source.location_kind,
       sum(allocation.quantity)
FROM equipment_reclassification_allocation AS allocation
JOIN public.equipment_balance AS source
  ON source.id = allocation.source_balance_id
GROUP BY
  allocation.target_equipment_id,
  source.warehouse_id,
  source.rental_item_id,
  source.location_kind;

CREATE TEMPORARY TABLE existing_reclassification_target (
  id uuid PRIMARY KEY,
  prior_version bigint NOT NULL,
  delta bigint NOT NULL,
  changed_at timestamptz NOT NULL
) ON COMMIT DROP;

INSERT INTO existing_reclassification_target(
  id,
  prior_version,
  delta,
  changed_at)
SELECT balance.id,
       balance.version,
       delta.quantity,
       clock_timestamp()
FROM equipment_reclassification_target_delta AS delta
JOIN public.equipment_balance AS balance
  ON balance.equipment_id = delta.target_equipment_id
 AND balance.warehouse_id = delta.warehouse_id
 AND balance.rental_item_id IS NOT DISTINCT FROM delta.rental_item_id
 AND balance.location_kind = delta.location_kind;

CREATE TEMPORARY TABLE created_reclassification_target (
  id uuid PRIMARY KEY
) ON COMMIT DROP;

WITH inserted AS (
  INSERT INTO public.equipment_balance(
    id,
    version,
    equipment_id,
    warehouse_id,
    rental_item_id,
    location_kind,
    quantity,
    created_at,
    updated_at)
  SELECT gen_random_uuid(),
         0,
         delta.target_equipment_id,
         delta.warehouse_id,
         delta.rental_item_id,
         delta.location_kind,
         delta.quantity,
         clock_timestamp(),
         clock_timestamp()
  FROM equipment_reclassification_target_delta AS delta
  WHERE NOT EXISTS (
    SELECT 1
    FROM public.equipment_balance AS balance
    WHERE balance.equipment_id = delta.target_equipment_id
      AND balance.warehouse_id = delta.warehouse_id
      AND balance.rental_item_id IS NOT DISTINCT FROM delta.rental_item_id
      AND balance.location_kind = delta.location_kind
  )
  RETURNING id
)
INSERT INTO created_reclassification_target(id)
SELECT id
FROM inserted;

UPDATE public.equipment_balance AS balance
SET version = balance.version + 1,
    quantity = balance.quantity + changed.delta,
    updated_at = changed.changed_at
FROM existing_reclassification_target AS changed
WHERE balance.id = changed.id
  AND balance.version = changed.prior_version;

INSERT INTO equipment_normalization_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  topic,
  payload,
  snapshot,
  recorded_at,
  initialize_stream)
SELECT gen_random_uuid(),
       'EQUIPMENT_BALANCE',
       balance.id,
       balance.version,
       'asset.equipment-balance.changed.v1',
       'rwms.asset.equipment-balance.v1',
       jsonb_build_object(
         'balanceId', balance.id::text,
         'equipmentId', balance.equipment_id::text,
         'warehouseId', balance.warehouse_id::text,
         'rentalItemId', balance.rental_item_id::text,
         'locationKind', balance.location_kind,
         'quantity', balance.quantity),
       jsonb_build_object(
         'balanceId', balance.id::text,
         'equipmentId', balance.equipment_id::text,
         'warehouseId', balance.warehouse_id::text,
         'rentalItemId', balance.rental_item_id::text,
         'locationKind', balance.location_kind,
         'quantity', balance.quantity),
       balance.created_at,
       true
FROM created_reclassification_target AS created
JOIN public.equipment_balance AS balance ON balance.id = created.id;

INSERT INTO equipment_normalization_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  topic,
  payload,
  snapshot,
  recorded_at,
  initialize_stream)
SELECT gen_random_uuid(),
       'EQUIPMENT_BALANCE',
       balance.id,
       balance.version,
       'asset.equipment-balance.changed.v1',
       'rwms.asset.equipment-balance.v1',
       jsonb_build_object(
         'balanceId', balance.id::text,
         'equipmentId', balance.equipment_id::text,
         'warehouseId', balance.warehouse_id::text,
         'rentalItemId', balance.rental_item_id::text,
         'locationKind', balance.location_kind,
         'quantity', balance.quantity),
       jsonb_build_object(
         'balanceId', balance.id::text,
         'equipmentId', balance.equipment_id::text,
         'warehouseId', balance.warehouse_id::text,
         'rentalItemId', balance.rental_item_id::text,
         'locationKind', balance.location_kind,
         'quantity', balance.quantity),
       changed.changed_at,
       false
FROM existing_reclassification_target AS changed
JOIN public.equipment_balance AS balance ON balance.id = changed.id;

CREATE TEMPORARY TABLE cleared_legacy_balance (
  id uuid PRIMARY KEY,
  prior_version bigint NOT NULL,
  prior_quantity bigint NOT NULL CHECK (prior_quantity > 0),
  cleared_at timestamptz NOT NULL
) ON COMMIT DROP;

INSERT INTO cleared_legacy_balance(
  id,
  prior_version,
  prior_quantity,
  cleared_at)
SELECT balance.id, balance.version, balance.quantity, clock_timestamp()
FROM public.equipment_balance AS balance
JOIN public.equipment_catalog_item AS item ON item.id = balance.equipment_id
WHERE item.code IN (
    'TABLE',
    'OFFICE_TABLE',
    'BENCH',
    'CHAIR',
    'BED',
    'BUNK_BED',
    'WARDROBE',
    'CONVECTOR',
    'AIR_CONDITIONER')
  AND balance.quantity > 0;

UPDATE public.equipment_balance AS balance
SET version = balance.version + 1,
    quantity = 0,
    updated_at = changed.cleared_at
FROM cleared_legacy_balance AS changed
WHERE balance.id = changed.id
  AND balance.version = changed.prior_version;

INSERT INTO equipment_normalization_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  topic,
  payload,
  snapshot,
  recorded_at,
  initialize_stream)
SELECT gen_random_uuid(),
       'EQUIPMENT_BALANCE',
       balance.id,
       balance.version,
       'asset.equipment-balance.changed.v1',
       'rwms.asset.equipment-balance.v1',
       jsonb_build_object(
         'balanceId', balance.id::text,
         'equipmentId', balance.equipment_id::text,
         'warehouseId', balance.warehouse_id::text,
         'rentalItemId', balance.rental_item_id::text,
         'locationKind', balance.location_kind,
         'quantity', balance.quantity),
       jsonb_build_object(
         'balanceId', balance.id::text,
         'equipmentId', balance.equipment_id::text,
         'warehouseId', balance.warehouse_id::text,
         'rentalItemId', balance.rental_item_id::text,
         'locationKind', balance.location_kind,
         'quantity', balance.quantity),
       changed.cleared_at,
       false
FROM cleared_legacy_balance AS changed
JOIN public.equipment_balance AS balance ON balance.id = changed.id;

-- Only the eleven estimate-catalog furniture identities remain operational.
CREATE TEMPORARY TABLE deactivated_legacy_equipment (
  id uuid PRIMARY KEY,
  prior_version bigint NOT NULL,
  changed_at timestamptz NOT NULL
) ON COMMIT DROP;

INSERT INTO deactivated_legacy_equipment(id, prior_version, changed_at)
SELECT item.id, item.version, clock_timestamp()
FROM public.equipment_catalog_item AS item
LEFT JOIN canonical_equipment AS canonical ON canonical.id = item.id
WHERE canonical.id IS NULL
  AND item.active;

UPDATE public.equipment_catalog_item AS item
SET active = false,
    version = item.version + 1,
    updated_at = changed.changed_at
FROM deactivated_legacy_equipment AS changed
WHERE item.id = changed.id
  AND item.version = changed.prior_version;

INSERT INTO equipment_normalization_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  topic,
  payload,
  snapshot,
  recorded_at,
  initialize_stream)
SELECT gen_random_uuid(),
       'EQUIPMENT_CATALOG',
       item.id,
       item.version,
       'asset.equipment-catalog.changed.v1',
       'rwms.asset.equipment-catalog.v1',
       jsonb_build_object(
         'equipmentId', item.id::text,
         'code', item.code,
         'category', item.category,
         'active', item.active),
       jsonb_build_object(
         'equipmentId', item.id::text,
         'version', item.version,
         'code', item.code,
         'name', item.name,
         'category', item.category,
         'active', item.active,
         'comment', item.comment),
       changed.changed_at,
       false
FROM deactivated_legacy_equipment AS changed
JOIN public.equipment_catalog_item AS item ON item.id = changed.id;

-- Refuse to advance an incoherent event stream. The Flyway transaction rolls
-- every projection change back if any current aggregate lacks its append-only
-- head, latest event, snapshot, checkpoint or outbox evidence.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM equipment_normalization_event AS event
    JOIN public.event_stream_head AS head
      ON head.aggregate_type = event.aggregate_type
     AND head.aggregate_id = event.aggregate_id::text
    WHERE event.initialize_stream
  ) THEN
    RAISE EXCEPTION
      'new equipment normalization aggregate already has an event stream';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM equipment_normalization_event AS event
    LEFT JOIN public.event_stream_head AS head
      ON head.aggregate_type = event.aggregate_type
     AND head.aggregate_id = event.aggregate_id::text
     AND head.current_version = event.aggregate_version - 1
    LEFT JOIN public.domain_event AS latest_event
      ON latest_event.event_id = head.last_event_id
     AND latest_event.aggregate_type = head.aggregate_type
     AND latest_event.aggregate_id = head.aggregate_id
     AND latest_event.aggregate_version = head.current_version
    LEFT JOIN public.aggregate_snapshot AS snapshot
      ON snapshot.aggregate_type = head.aggregate_type
     AND snapshot.aggregate_id = head.aggregate_id
     AND snapshot.aggregate_version = head.current_version
    LEFT JOIN public.projection_checkpoint AS checkpoint
      ON checkpoint.projection_name = 'asset-live-v1'
     AND checkpoint.aggregate_type = head.aggregate_type
     AND checkpoint.aggregate_id = head.aggregate_id
     AND checkpoint.aggregate_version = head.current_version
    LEFT JOIN public.outbox_event AS outbox
      ON outbox.event_id = head.last_event_id
     AND outbox.aggregate_type = head.aggregate_type
     AND outbox.aggregate_id = head.aggregate_id
     AND outbox.aggregate_version = head.current_version
    WHERE NOT event.initialize_stream
      AND (
        head.aggregate_id IS NULL
        OR latest_event.event_id IS NULL
        OR snapshot.aggregate_id IS NULL
        OR checkpoint.aggregate_id IS NULL
        OR outbox.event_id IS NULL
        OR checkpoint.projection_sha256 <> snapshot.state_sha256
        OR snapshot.state_sha256 <>
          encode(sha256(convert_to(snapshot.state::text, 'UTF8')), 'hex')
      )
  ) THEN
    RAISE EXCEPTION
      'equipment normalization requires coherent current event state';
  END IF;
END $$;

UPDATE public.event_stream_head AS head
SET current_version = event.aggregate_version,
    last_event_id = event.event_id,
    updated_at = event.recorded_at
FROM equipment_normalization_event AS event
WHERE NOT event.initialize_stream
  AND head.aggregate_type = event.aggregate_type
  AND head.aggregate_id = event.aggregate_id::text
  AND head.current_version = event.aggregate_version - 1;

INSERT INTO public.event_stream_head(
  aggregate_type,
  aggregate_id,
  current_version,
  last_event_id,
  updated_at)
SELECT event.aggregate_type,
       event.aggregate_id::text,
       event.aggregate_version,
       event.event_id,
       event.recorded_at
FROM equipment_normalization_event AS event
WHERE event.initialize_stream;

INSERT INTO public.domain_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  event_version,
  occurred_at,
  recorded_at,
  correlation_id,
  causation_id,
  actor_ref,
  payload,
  payload_sha256,
  baseline)
SELECT event.event_id,
       event.aggregate_type,
       event.aggregate_id::text,
       event.aggregate_version,
       event.event_type,
       1,
       event.recorded_at,
       event.recorded_at,
       event.event_id,
       null,
       null,
       event.payload,
       encode(sha256(convert_to(event.payload::text, 'UTF8')), 'hex'),
       false
FROM equipment_normalization_event AS event;

INSERT INTO public.aggregate_snapshot(
  aggregate_type,
  aggregate_id,
  aggregate_version,
  state,
  state_sha256,
  recorded_at)
SELECT event.aggregate_type,
       event.aggregate_id::text,
       event.aggregate_version,
       event.snapshot,
       encode(sha256(convert_to(event.snapshot::text, 'UTF8')), 'hex'),
       event.recorded_at
FROM equipment_normalization_event AS event;

INSERT INTO public.projection_checkpoint(
  projection_name,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  projection_sha256,
  updated_at)
SELECT 'asset-live-v1',
       event.aggregate_type,
       event.aggregate_id::text,
       event.aggregate_version,
       encode(sha256(convert_to(event.snapshot::text, 'UTF8')), 'hex'),
       event.recorded_at
FROM equipment_normalization_event AS event
ON CONFLICT (projection_name, aggregate_type, aggregate_id)
DO UPDATE SET
  aggregate_version = EXCLUDED.aggregate_version,
  projection_sha256 = EXCLUDED.projection_sha256,
  updated_at = EXCLUDED.updated_at;

WITH envelopes AS (
  SELECT event.*,
         jsonb_build_object(
           'envelopeVersion', 2,
           'eventId', event.event_id::text,
           'eventType', event.event_type,
           'eventVersion', 1,
           'occurredAt', event.recorded_at,
           'recordedAt', event.recorded_at,
           'producer', 'asset-service',
           'aggregateType', event.aggregate_type,
           'aggregateId', event.aggregate_id::text,
           'aggregateVersion', event.aggregate_version,
           'correlation', jsonb_build_object(
             'correlationId', event.event_id::text,
             'causationId', null),
           'actorRef', null,
           'payload', event.payload) AS envelope
  FROM equipment_normalization_event AS event
)
INSERT INTO public.outbox_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  topic,
  envelope_body,
  envelope_sha256,
  status,
  attempt_count,
  next_attempt_at,
  created_at)
SELECT event_id,
       aggregate_type,
       aggregate_id::text,
       aggregate_version,
       event_type,
       topic,
       envelope,
       encode(sha256(convert_to(envelope::text, 'UTF8')), 'hex'),
       'PENDING',
       0,
       recorded_at,
       recorded_at
FROM envelopes;

DO $$
BEGIN
  IF (
    SELECT count(*)
    FROM public.equipment_catalog_item
    WHERE active
  ) <> 11
  OR EXISTS (
    SELECT 1
    FROM public.equipment_catalog_item AS item
    LEFT JOIN canonical_equipment AS canonical ON canonical.id = item.id
    WHERE item.active
      AND canonical.id IS NULL
  ) THEN
    RAISE EXCEPTION
      'equipment normalization did not leave exactly eleven active canonical items';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.equipment_balance AS balance
    JOIN public.equipment_catalog_item AS item ON item.id = balance.equipment_id
    LEFT JOIN canonical_equipment AS canonical ON canonical.id = item.id
    WHERE canonical.id IS NULL
      AND balance.quantity <> 0
  ) THEN
    RAISE EXCEPTION
      'legacy equipment still has a non-zero current balance';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.equipment_allocation_hold AS hold
    JOIN public.equipment_catalog_item AS item ON item.id = hold.equipment_id
    LEFT JOIN canonical_equipment AS canonical ON canonical.id = item.id
    WHERE canonical.id IS NULL
      AND hold.state = 'ACTIVE'
  )
  OR EXISTS (
    SELECT 1
    FROM public.order_equipment_reservation AS reservation
    JOIN public.equipment_catalog_item AS item ON item.id = reservation.equipment_id
    LEFT JOIN canonical_equipment AS canonical ON canonical.id = item.id
    WHERE canonical.id IS NULL
      AND reservation.state = 'ACTIVE'
  ) THEN
    RAISE EXCEPTION
      'legacy equipment still has an active reservation';
  END IF;

  IF (
    SELECT coalesce(sum(allocation.quantity), 0)
    FROM equipment_reclassification_allocation AS allocation
  ) <> (
    SELECT coalesce(sum(changed.prior_quantity), 0)
    FROM cleared_legacy_balance AS changed
    JOIN public.equipment_balance AS balance ON balance.id = changed.id
    JOIN public.equipment_catalog_item AS item ON item.id = balance.equipment_id
    WHERE item.code <> 'AIR_CONDITIONER'
  ) THEN
    RAISE EXCEPTION
      'equipment normalization did not preserve classified quantities';
  END IF;

  IF (
    SELECT coalesce(sum(allocation.quantity), 0)
    FROM equipment_reclassification_allocation AS allocation
  ) <> (
    SELECT coalesce(sum(delta.quantity), 0)
    FROM equipment_reclassification_target_delta AS delta
  ) THEN
    RAISE EXCEPTION
      'equipment normalization target aggregation changed a quantity';
  END IF;
END $$;
