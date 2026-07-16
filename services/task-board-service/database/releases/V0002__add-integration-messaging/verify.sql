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
