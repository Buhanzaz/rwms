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
