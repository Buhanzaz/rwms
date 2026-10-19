-- Exact preflight for adopting an existing task-board database into Flyway version 4.
-- Run with psql using ON_ERROR_STOP before the explicit Flyway baseline command.
-- This file never mutates schema or data.

-- Monotonic exact verification: every adopted V1 column, constraint and index
-- must retain its catalog definition. Later releases may add new objects.
DO $rwms$
DECLARE
  mismatch text;
BEGIN
  WITH expected(table_name, column_names, column_digest, constraint_names, constraint_digest,
                index_names, index_digest) AS (VALUES
    ('board_task','id,version,warehouse_id,external_task_id,title,unit_number,description,status,planned_duration_minutes,deadline_at,done_at','88de53c30ef66b6305fc698b351b5367','board_task_pkey,uk_board_task_external','996241a49be75c941eb1ca9ce67b0fa7','board_task_pkey,idx_board_task_warehouse_status,uk_board_task_external','a25800fb30ea9936fe092c1ebeb1304c'),
    ('queue_entry','id,version,task_id,queue_id,queue_code,route_index,queue_position,entry_type,status,task_text,planned_duration_minutes,active_started_at,paused_at,done_at,active_work_seconds,pause_origin','7ebc78b60e127cfdc262cfd47f274bfe','fk_queue_entry_queue,fk_queue_entry_task,queue_entry_pkey,uk_queue_entry_route','997eb96af5b1d5a5fa24213f6c30dd87','idx_queue_entry_board,queue_entry_pkey,uk_queue_entry_route','a3dd110bb9f164f6f1d9075cb694b495'),
    ('queue_usage_reference','id,version,revision_marker,queue_id,reference_type,external_reference_id','df79bc83ef53f42034de775e5d1c7c01','fk_queue_usage_queue,queue_usage_reference_pkey,uk_queue_usage_reference','feb6d68c875bee5cebdc7b896eb5ad46','queue_usage_reference_pkey,uk_queue_usage_reference','2c2f19913c18f16cc63f19f2b458f56f'),
    ('task_assignment','id,version,queue_entry_id,worker_group_id,worker_id,worker_name_snapshot,group_name_snapshot,status,assigned_at,started_at,paused_at,finished_at','d4db33308a85ac1c7ccc159fe78d88b3','fk_task_assignment_entry,fk_task_assignment_group,fk_task_assignment_worker,task_assignment_pkey','608d1bd63cebb6a07a0fc316bc9a896f','idx_task_assignment_worker_status,task_assignment_pkey','7f2ad4ba2cca1d19e8379359468ef05d'),
    ('task_auto_interruption','id,version,worker_id,interrupted_entry_id,interrupting_entry_id,active,created_at,resolved_at','6beafb604d3fb1fb6e29ee01fef76c83','fk_auto_interruption_interrupted,fk_auto_interruption_interrupting,fk_auto_interruption_worker,task_auto_interruption_pkey,uk_auto_interruption','551827c7ae6b75a3b98ceba724b56148','task_auto_interruption_pkey,uk_auto_interruption','d76bc13b6f7393624e841b32b954e9ce'),
    ('task_time_event','id,version,queue_entry_id,worker_id,worker_name_snapshot,group_name_snapshot,event_type,reason,created_at,related_entry_id','ba40c1b5842d742708582e110bd553aa','fk_task_time_event_entry,fk_task_time_event_worker,task_time_event_pkey','ca9ba48f606de71e95df6d4b680de904','task_time_event_pkey','c5b86733a81006b2652525629b22bd9d'),
    ('work_queue','id,version,revision_marker,warehouse_id,code,name,description,queue_type,sort_order,active,hidden,collapsed,holding_period_minutes,notification_threshold,notify_when_threshold_reached','c27c49371af3052bede0d80829d96846','ck_work_queue_holding,uk_work_queue_code,work_queue_pkey','4681efc1ee968fbb82cb6cc3ca1b2ff9','idx_work_queue_order,uk_work_queue_code,work_queue_pkey','5ee87a09d982a427b5c18ab6b4dda556'),
    ('work_queue_class_binding','id,version,queue_id,worker_class_id,stop_task_on_take','e69eb873e7e91c719b4f56d40121cc16','fk_work_queue_binding_class,fk_work_queue_binding_queue,uk_work_queue_binding,work_queue_class_binding_pkey','a0aead8ac23b9810e08d2657559acee7','uk_work_queue_binding,work_queue_class_binding_pkey','9515d70be740a837b06070d5483585ff'),
    ('worker','id,version,revision_marker,warehouse_id,first_name,last_name,middle_name,display_name,active,comment_text,app_login,credential_status,credential_error','7f8c6a5753097cf60781e806e41b3467','uk_worker_app_login,worker_pkey','a78fd6a053510739503a3a830b6f548c','idx_worker_warehouse,uk_worker_app_login,worker_pkey','e775a94333982e7c331a7c003257f689'),
    ('worker_class','id,version,revision_marker,code,name,description,comment_text,sort_order,active','c10615631a67efe1dfc296fdfacdf5c9','uk_worker_class_code,worker_class_pkey','057d7c0e857e54d76f37bef236eedeb0','uk_worker_class_code,worker_class_pkey','66e0058dc2d7053b487d7e8fe2918667'),
    ('worker_class_assignment','id,version,worker_id,worker_class_id,active,comment_text','56b2b755246da2d05533f3420a867156','fk_worker_class_assignment_class,fk_worker_class_assignment_worker,uk_worker_class_assignment,worker_class_assignment_pkey','cb6e97aaa46fe8db0addd79d95027a84','uk_worker_class_assignment,worker_class_assignment_pkey','8ea02088d0ca3b0ea4b671e9827b959a'),
    ('worker_deletion_intent','id,version,worker_id,status,last_error,created_at,updated_at','89d0e94249a814112e7c01c3f7d8422c','fk_worker_deletion_intent_worker,uk_worker_deletion_intent_worker,worker_deletion_intent_pkey','c7aa4062ea6aa7cec0ecbc342ac145e1','uk_worker_deletion_intent_worker,worker_deletion_intent_pkey','6e302c216ce17d62449076d46f025f2e'),
    ('worker_group','id,version,revision_marker,warehouse_id,worker_class_id,name,description,active','412a011e79e7cea22a7825bcdfac9b17','fk_worker_group_class,uk_worker_group_name,worker_group_pkey','aedaa6b9eaa549cef19117061d623e60','idx_worker_group_warehouse,uk_worker_group_name,worker_group_pkey','04c63103dab9eafe1758e102b58f3310'),
    ('worker_group_member','id,version,worker_group_id,worker_id,role_in_group,active','a5889decd9242ba8837bb28c9761c553','fk_worker_group_member_group,fk_worker_group_member_worker,uk_worker_group_member,worker_group_member_pkey','b658ee195d0c39eac0a7801ac154dd64','uk_worker_group_member,worker_group_member_pkey','d009158daa7556fefae7a20aca0285a3')
  ), actual AS (
    SELECT e.table_name,
      md5(string_agg(a.attname||':'||pg_catalog.format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||coalesce(pg_get_expr(ad.adbin,ad.adrelid),''), '|' ORDER BY a.attnum)) column_digest,
      (SELECT md5(string_agg(con.conname||':'||con.contype::text||':'||pg_get_constraintdef(con.oid,true), '|' ORDER BY con.conname))
         FROM pg_constraint con WHERE con.conrelid=c.oid AND con.conname=ANY(string_to_array(e.constraint_names,','))) constraint_digest,
      (SELECT md5(string_agg(ic.relname||':'||pg_get_indexdef(i.indexrelid), '|' ORDER BY ic.relname))
         FROM pg_index i JOIN pg_class ic ON ic.oid=i.indexrelid
        WHERE i.indrelid=c.oid AND ic.relname=ANY(string_to_array(e.index_names,','))) index_digest,
      count(a.attname) column_count
    FROM expected e
    LEFT JOIN pg_class c ON c.relname=e.table_name AND c.relkind='r'
      AND c.relnamespace='public'::regnamespace
    LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
      AND a.attname=ANY(string_to_array(e.column_names,','))
    LEFT JOIN pg_attrdef ad ON ad.adrelid=c.oid AND ad.adnum=a.attnum
    GROUP BY e.table_name,e.column_names,e.constraint_names,e.index_names,c.oid
  )
  SELECT string_agg(e.table_name, ', ' ORDER BY e.table_name) INTO mismatch
    FROM expected e JOIN actual a USING(table_name)
   WHERE a.column_count <> cardinality(string_to_array(e.column_names,','))
      OR a.column_digest IS DISTINCT FROM e.column_digest
      OR a.constraint_digest IS DISTINCT FROM e.constraint_digest
      OR a.index_digest IS DISTINCT FROM e.index_digest;
  IF mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'V0001 task-board catalog mismatch: %', mismatch;
  END IF;
END $rwms$;

-- Monotonic exact verification for the V2 integration additions. V1 is
-- verified independently before this script is executed by the release runner.
DO $rwms$
DECLARE
  mismatch text;
BEGIN
  WITH expected(table_name, column_names, column_digest, constraint_names, constraint_digest,
                index_names, index_digest) AS (VALUES
    ('board_task','id,version,warehouse_id,external_task_id,title,unit_number,description,status,planned_duration_minutes,deadline_at,done_at,request_fingerprint','e185d411c24ccec2045c82bbda5a9ea6','board_task_pkey,ck_board_task_request_fingerprint,uk_board_task_external','e8e28195156ec2795fe69f39e76e692c','board_task_pkey,idx_board_task_warehouse_status,uk_board_task_external','a25800fb30ea9936fe092c1ebeb1304c'),
    ('task_board_inbox','consumer_name,event_id,event_hash,event_type,event_version,received_at,processed_at','a11ee7ead2508e466f9a913c2543f547','ck_task_board_inbox_hash,ck_task_board_inbox_version,pk_task_board_inbox','3fdcf654009dc249174b937489f382f1','pk_task_board_inbox','8c28486fbe258210fc092cedcc918bd1'),
    ('task_board_outbox','event_id,event_type,event_version,routing_key,aggregate_type,aggregate_id,aggregate_version,envelope_body,envelope_sha256,correlation_id,causation_id,actor_id,actor_type,actor_display_name,occurred_at,created_at,status,attempt_count,next_attempt_at,lease_until,lease_owner,lease_token,published_at,last_error','f22dc1354b39c5b116368e329d36fba2','ck_task_board_outbox_aggregate_version,ck_task_board_outbox_attempt_count,ck_task_board_outbox_body,ck_task_board_outbox_contract,ck_task_board_outbox_event_version,ck_task_board_outbox_lease,ck_task_board_outbox_published,ck_task_board_outbox_status,task_board_outbox_pkey,uk_task_board_outbox_semantic','24af471025b5066f1440bde32c9f21d1','idx_task_board_outbox_aggregate_head,idx_task_board_outbox_expired_lease,idx_task_board_outbox_pending,task_board_outbox_pkey,uk_task_board_outbox_semantic','3def9a35a956a936fea87804133d5bae')
  ), actual AS (
    SELECT e.table_name,
      md5(string_agg(a.attname||':'||pg_catalog.format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||coalesce(pg_get_expr(ad.adbin,ad.adrelid),''), '|' ORDER BY a.attnum)) column_digest,
      (SELECT md5(string_agg(con.conname||':'||con.contype::text||':'||pg_get_constraintdef(con.oid,true), '|' ORDER BY con.conname))
         FROM pg_constraint con WHERE con.conrelid=c.oid AND con.conname=ANY(string_to_array(e.constraint_names,','))) constraint_digest,
      (SELECT md5(string_agg(ic.relname||':'||pg_get_indexdef(i.indexrelid), '|' ORDER BY ic.relname))
         FROM pg_index i JOIN pg_class ic ON ic.oid=i.indexrelid
        WHERE i.indrelid=c.oid AND ic.relname=ANY(string_to_array(e.index_names,','))) index_digest,
      count(a.attname) column_count
    FROM expected e
    LEFT JOIN pg_class c ON c.relname=e.table_name AND c.relkind='r'
      AND c.relnamespace='public'::regnamespace
    LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
      AND a.attname=ANY(string_to_array(e.column_names,','))
    LEFT JOIN pg_attrdef ad ON ad.adrelid=c.oid AND ad.adnum=a.attnum
    GROUP BY e.table_name,e.column_names,e.constraint_names,e.index_names,c.oid
  )
  SELECT string_agg(e.table_name, ', ' ORDER BY e.table_name) INTO mismatch
    FROM expected e JOIN actual a USING(table_name)
   WHERE a.column_count <> cardinality(string_to_array(e.column_names,','))
      OR a.column_digest IS DISTINCT FROM e.column_digest
      OR a.constraint_digest IS DISTINCT FROM e.constraint_digest
      OR a.index_digest IS DISTINCT FROM e.index_digest;
  IF mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'V0002 task-board catalog mismatch: %', mismatch;
  END IF;
END $rwms$;

DO $rwms$
DECLARE
  actual text;
BEGIN
  SELECT pg_get_indexdef('public.uk_worker_class_code_ci'::regclass) INTO actual;
  IF actual <> 'CREATE UNIQUE INDEX uk_worker_class_code_ci ON public.worker_class USING btree (lower((code)::text))' THEN
    RAISE EXCEPTION 'Unexpected uk_worker_class_code_ci definition';
  END IF;

  SELECT pg_get_indexdef('public.uk_worker_app_login_ci'::regclass) INTO actual;
  IF actual <> 'CREATE UNIQUE INDEX uk_worker_app_login_ci ON public.worker USING btree (lower((app_login)::text)) WHERE (app_login IS NOT NULL)' THEN
    RAISE EXCEPTION 'Unexpected uk_worker_app_login_ci definition';
  END IF;

  SELECT pg_get_indexdef('public.uk_work_queue_code_ci'::regclass) INTO actual;
  IF actual <> 'CREATE UNIQUE INDEX uk_work_queue_code_ci ON public.work_queue USING btree (warehouse_id, lower((code)::text))' THEN
    RAISE EXCEPTION 'Unexpected uk_work_queue_code_ci definition';
  END IF;
END $rwms$;

DO $rwms$
DECLARE
  mismatch text;
  actual_definition text;
  actual_validated boolean;
BEGIN
  WITH expected(column_name, data_type, udt_name, character_maximum_length) AS (VALUES
    ('credential_operation_id', 'uuid', 'uuid', NULL::bigint),
    ('credential_operation_type', 'character varying', 'varchar', 32::bigint),
    ('credential_operation_started_at', 'timestamp with time zone', 'timestamptz', NULL::bigint)
  ), actual AS (
    SELECT column_name, data_type, udt_name, character_maximum_length,
           is_nullable, column_default
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'worker'
      AND column_name IN (
        'credential_operation_id',
        'credential_operation_type',
        'credential_operation_started_at'
      )
  )
  SELECT string_agg(expected.column_name, ', ' ORDER BY expected.column_name)
    INTO mismatch
  FROM expected
  LEFT JOIN actual USING (column_name)
  WHERE actual.column_name IS NULL
     OR actual.data_type IS DISTINCT FROM expected.data_type
     OR actual.udt_name IS DISTINCT FROM expected.udt_name
     OR actual.character_maximum_length IS DISTINCT FROM expected.character_maximum_length
     OR actual.is_nullable IS DISTINCT FROM 'YES'
     OR actual.column_default IS NOT NULL;

  IF mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'V0004 worker credential operation column mismatch: %', mismatch;
  END IF;

  SELECT pg_get_constraintdef(oid, true), convalidated
    INTO actual_definition, actual_validated
  FROM pg_constraint
  WHERE conrelid = 'public.worker'::regclass
    AND conname = 'ck_worker_credential_operation_type';

  IF actual_definition IS DISTINCT FROM
      'CHECK (credential_operation_type::text = ANY (ARRAY[''CONFIGURE''::character varying, ''RESET''::character varying, ''DISABLE''::character varying, ''CLEAR''::character varying, ''RECONCILE_DISABLE''::character varying]::text[]))'
     OR actual_validated IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Unexpected ck_worker_credential_operation_type definition';
  END IF;

  SELECT pg_get_constraintdef(oid, true), convalidated
    INTO actual_definition, actual_validated
  FROM pg_constraint
  WHERE conrelid = 'public.worker'::regclass
    AND conname = 'ck_worker_credential_operation_metadata';

  IF actual_definition IS DISTINCT FROM
      'CHECK (num_nonnulls(credential_operation_id, credential_operation_type, credential_operation_started_at) = ANY (ARRAY[0, 3]))'
     OR actual_validated IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Unexpected ck_worker_credential_operation_metadata definition';
  END IF;
END $rwms$;
-- F4MT exact final catalog and data compatibility gate. This check is run by
-- an operator before Flyway baseline version 4. It is deliberately stricter
-- than the monotonic historical release verifiers above.
DO $rwms$
DECLARE
  mismatch text;
BEGIN
  WITH expected(table_name, column_count) AS (VALUES
    ('board_task', 12),
    ('queue_entry', 16),
    ('queue_usage_reference', 6),
    ('task_assignment', 12),
    ('task_auto_interruption', 8),
    ('task_board_inbox', 7),
    ('task_board_outbox', 24),
    ('task_time_event', 10),
    ('work_queue', 15),
    ('work_queue_class_binding', 5),
    ('worker', 16),
    ('worker_class', 9),
    ('worker_class_assignment', 6),
    ('worker_deletion_intent', 7),
    ('worker_group', 8),
    ('worker_group_member', 6)
  ),
  actual AS (
    SELECT table_name, count(*)::integer AS column_count
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name IN (SELECT table_name FROM expected)
    GROUP BY table_name
  )
  SELECT string_agg(e.table_name || ' expected=' || e.column_count ||
                    ' actual=' || coalesce(a.column_count::text, 'missing'),
                    ', ' ORDER BY e.table_name)
    INTO mismatch
    FROM expected e
    LEFT JOIN actual a USING (table_name)
   WHERE a.column_count IS DISTINCT FROM e.column_count;

  IF mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'Task-board version 4 exact column-count mismatch: %', mismatch;
  END IF;

  IF to_regclass('public.rwms_schema_history') IS NULL
     OR to_regclass('public.databasechangelog') IS NULL
     OR to_regclass('public.databasechangeloglock') IS NULL THEN
    RAISE EXCEPTION
      'Historical migration evidence tables rwms_schema_history/databasechangelog/databasechangeloglock are required';
  END IF;

  WITH expected(table_name) AS (VALUES
    ('board_task'), ('queue_entry'), ('queue_usage_reference'), ('task_assignment'),
    ('task_auto_interruption'), ('task_board_inbox'), ('task_board_outbox'),
    ('task_time_event'), ('work_queue'), ('work_queue_class_binding'), ('worker'),
    ('worker_class'), ('worker_class_assignment'), ('worker_deletion_intent'),
    ('worker_group'), ('worker_group_member'), ('rwms_schema_history'),
    ('databasechangelog'), ('databasechangeloglock')
  ), actual AS (
    SELECT table_name
    FROM information_schema.tables
    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
  )
  SELECT string_agg(coalesce(e.table_name, a.table_name), ', '
                    ORDER BY coalesce(e.table_name, a.table_name))
    INTO mismatch
    FROM expected e
    FULL JOIN actual a USING (table_name)
   WHERE e.table_name IS NULL OR a.table_name IS NULL;

  IF mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'Task-board version 4 exact table-set mismatch: %', mismatch;
  END IF;
END $rwms$;

DO $rwms$
DECLARE
  mismatch text;
BEGIN
  WITH expected(table_name, constraint_count, index_count) AS (VALUES
    ('board_task', 3, 3),
    ('queue_entry', 4, 3),
    ('queue_usage_reference', 3, 2),
    ('task_assignment', 4, 2),
    ('task_auto_interruption', 5, 2),
    ('task_board_inbox', 3, 1),
    ('task_board_outbox', 10, 5),
    ('task_time_event', 3, 1),
    ('work_queue', 3, 4),
    ('work_queue_class_binding', 4, 2),
    ('worker', 4, 4),
    ('worker_class', 2, 3),
    ('worker_class_assignment', 4, 2),
    ('worker_deletion_intent', 3, 2),
    ('worker_group', 3, 3),
    ('worker_group_member', 4, 2)
  ), actual AS (
    SELECT e.table_name,
           (SELECT count(*)::integer
              FROM pg_constraint c
             WHERE c.conrelid = ('public.' || e.table_name)::regclass) AS constraint_count,
           (SELECT count(*)::integer
              FROM pg_indexes i
             WHERE i.schemaname = 'public' AND i.tablename = e.table_name) AS index_count
      FROM expected e
  )
  SELECT string_agg(e.table_name || ' constraints=' || a.constraint_count || '/' ||
                    e.constraint_count || ' indexes=' || a.index_count || '/' || e.index_count,
                    ', ' ORDER BY e.table_name)
    INTO mismatch
    FROM expected e
    JOIN actual a USING (table_name)
   WHERE a.constraint_count <> e.constraint_count OR a.index_count <> e.index_count;

  IF mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'Task-board version 4 exact constraint/index count mismatch: %', mismatch;
  END IF;
END $rwms$;

DO $rwms$
DECLARE
  mismatch text;
BEGIN
  WITH expected(version, checksum) AS (VALUES
    ('0001', '1e1910dbeea555ac08af0b4948bfc97a59888a5499e847d5a605fc30c8106adb'),
    ('0002', '4a801d2a93c38ff7cd0bc59df5300a9d015d901f733fac1280ecb6e25b8b14b5'),
    ('0003', '31a451efd2d770c7260cae5ae80335ebc6c44582568c1dada5c92e0b2db3b9af'),
    ('0004', 'e07114131235f5de1dc6df68382a762c8abaeaf63db308bfbead3bb9bf4f23cc')
  ),
  actual AS (
    SELECT version, checksum
    FROM public.rwms_schema_history
  )
  SELECT string_agg(
           coalesce(e.version, a.version) || ' expected=' || coalesce(e.checksum, 'absent') ||
           ' actual=' || coalesce(a.checksum, 'absent'),
           ', ' ORDER BY coalesce(e.version, a.version))
    INTO mismatch
    FROM expected e
    FULL JOIN actual a USING (version)
   WHERE e.checksum IS DISTINCT FROM a.checksum;

  IF mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'Task-board historical release evidence mismatch: %', mismatch;
  END IF;
END $rwms$;

DO $rwms$
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.worker
    WHERE credential_status NOT IN ('NOT_CONFIGURED', 'PENDING', 'ACTIVE', 'ERROR')
       OR credential_operation_type IS NOT NULL
          AND credential_operation_type NOT IN (
            'CONFIGURE', 'RESET', 'DISABLE', 'CLEAR', 'RECONCILE_DISABLE')
  ) THEN
    RAISE EXCEPTION 'Unsupported worker enum value for version 4 JPA mappings';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.worker_deletion_intent
    WHERE status NOT IN ('PENDING_AUTH', 'AUTH_DELETED', 'ERROR')
  ) THEN
    RAISE EXCEPTION 'Unsupported worker_deletion_intent.status for version 4 JPA mappings';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.work_queue
    WHERE queue_type NOT IN ('MOVEMENT', 'REPAIR', 'HOLDING')
  ) THEN
    RAISE EXCEPTION 'Unsupported work_queue.queue_type for version 4 JPA mappings';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.board_task
    WHERE status NOT IN ('ACTIVE', 'DONE', 'CANCELLED')
  ) OR EXISTS (
    SELECT 1 FROM public.queue_entry
    WHERE entry_type NOT IN ('REAL', 'SHADOW')
       OR status NOT IN ('WAITING', 'IN_PROGRESS', 'PAUSED', 'DONE', 'CANCELLED')
       OR pause_origin IS NOT NULL AND pause_origin NOT IN ('MANUAL', 'AUTO')
  ) OR EXISTS (
    SELECT 1 FROM public.task_assignment
    WHERE status NOT IN ('ACTIVE', 'PAUSED', 'DONE', 'CANCELLED')
  ) OR EXISTS (
    SELECT 1 FROM public.task_time_event
    WHERE event_type NOT IN (
      'STARTED', 'PAUSED', 'RESUMED', 'FINISHED', 'CANCELLED',
      'AUTO_INTERRUPTED', 'AUTO_RESUMED')
  ) OR EXISTS (
    SELECT 1 FROM public.queue_usage_reference
    WHERE reference_type NOT IN ('REPAIR_PLAN', 'CATALOG_POSITION')
  ) THEN
    RAISE EXCEPTION 'Unsupported task-board enum value for version 4 JPA mappings';
  END IF;
END $rwms$;
