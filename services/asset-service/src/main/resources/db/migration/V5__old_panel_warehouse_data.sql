-- User-directed transfer of the warehouse registry from old-panel browser mocks
-- into the asset-service database. The migration keeps one authoritative copy
-- of every cabin, equipment catalog item, warehouse balance and cabin balance.

create temporary table old_panel_cabin_seed (
  global_index integer primary key,
  local_index integer not null,
  legacy_id varchar(32) not null,
  legacy_warehouse_id varchar(8) not null,
  id uuid not null unique,
  warehouse_id uuid not null,
  display_number varchar(128) not null unique,
  legacy_number varchar(128) not null,
  status varchar(64) not null,
  rental_type varchar(255) not null,
  dimensions varchar(255) not null,
  finishing varchar(255) not null,
  category varchar(255) not null,
  characteristics varchar(2000) not null,
  linoleum boolean not null,
  general_comment varchar(4000),
  shipment_date varchar(10),
  tenant varchar(255),
  price bigint,
  photo_count integer not null,
  passport jsonb not null,
  created_at timestamptz not null
) on commit drop;

with source as (
  select local_index, local_index as global_index, 'spb'::varchar as legacy_warehouse_id,
         '00000000-0000-0000-0000-000000000001'::uuid as warehouse_id
  from generate_series(1, 120) local_index
  union all
  select local_index, 120 + local_index, 'msk'::varchar,
         '00000000-0000-0000-0000-000000000002'::uuid
  from generate_series(1, 75) local_index
), core as (
  select source.*,
         local_index - 1 as zero_index,
         legacy_warehouse_id || '-' || local_index as legacy_id,
         format('БЫТ-%s', lpad(global_index::text, 3, '0')) as display_number,
         format('БЫТ-%s', lpad(local_index::text, 3, '0')) as legacy_number,
         (array[
           'FREE','RENTED','AFTER_RENT','WAITING_ESTIMATE_CONFIRMATION','BOOKED',
           'REPAIR','CAPITAL_REPAIR','USED_SALE','WAREHOUSE','OWN_NEEDS'
         ])[(local_index - 1) % 10 + 1] as status,
         case when (local_index - 1) % 3 = 0 then 0 else (local_index - 1) % 15 + 1 end as photo_count
  from source
), enriched as (
  select core.*,
         (array[
           'БК-1','БК-2','БК-3','БК-4','БК-5','БК-6','БК-Склад','БК-Санблок',
           'БК-Модуль из 2х','БК-Модуль из 3','БК-Пост охраны'
         ])[zero_index % 11 + 1] as rental_type,
         (array['2x2','2.4x2','2.4x2.4','2.4x3','2.4x4','2.4x5','2.4x6','3x3','4.8x6','7.2x6'])
           [zero_index % 10 + 1] as dimensions,
         (array['ДВП','ЛДСП','ПВХ','ОСБ','Вагонка','СМЛО','Сэндвич'])
           [zero_index % 7 + 1] as finishing,
         (array['Обычная','ИТР','Новая','Санблок'])[zero_index % 4 + 1] as category,
         (array[
           'Пластиковое окно','Электрика КК','Электрика КК + УЗО',
           'Электрика КК + УЗО + счётчик','Электрика КК + счётчик',
           'Металлическая дверь, кондиционер','Две лампы','Мама-папа'
         ])[zero_index % 8 + 1] as characteristics,
         zero_index % 2 = 0 as linoleum,
         case
           when zero_index % 5 = 0 then 'Нужна проверка перед выдачей клиенту'
           when zero_index % 7 = 0 then 'Есть замечания по внутренней отделке'
           else null
         end as general_comment,
         case when status = 'RENTED'
           then format('2026-05-%s', lpad((zero_index % 27 + 1)::text, 2, '0'))
           else null
         end as shipment_date,
         case when status = 'RENTED'
           then case when zero_index % 2 = 0 then 'ООО СтройПроект' else 'ИП Петров А.В.' end
           else null
         end as tenant,
         case when zero_index % 3 = 0 then 30000 + zero_index * 250 else null end as price,
         ('51000000-0000-4000-8000-' || lpad(global_index::text, 12, '0'))::uuid as id,
         timestamptz '2026-07-19 00:00:00+03' + global_index * interval '10 seconds' as created_at
  from core
), media as (
  select enriched.*,
         coalesce((
           select jsonb_agg(jsonb_build_object(
             'id', legacy_id || '-photo-' || photo_index,
             'url', photo_url,
             'variants', jsonb_build_object(
               'small', jsonb_build_object(
                 'url', replace(replace(photo_url, 'q=80', 'q=70'), 'w=900', 'w=360') || '&fm=webp',
                 'width', 360,
                 'mimeType', 'image/webp'),
               'largeWebp', jsonb_build_object(
                 'url', replace(replace(photo_url, 'q=80', 'q=90'), 'w=900', 'w=1800') || '&fm=webp',
                 'width', 1800,
                 'mimeType', 'image/webp')),
             'capturedAt', null,
             'capturedAtKnown', false) order by photo_index)
           from generate_series(1, photo_count) photo_index
           cross join lateral (
             select (array[
               'https://images.unsplash.com/photo-1494526585095-c41746248156?q=80&w=900&auto=format&fit=crop',
               'https://images.unsplash.com/photo-1518780664697-55e3ad937233?q=80&w=900&auto=format&fit=crop',
               'https://images.unsplash.com/photo-1564013799919-ab600027ffc6?q=80&w=900&auto=format&fit=crop',
               'https://images.unsplash.com/photo-1570129477492-45c003edd2be?q=80&w=900&auto=format&fit=crop'
             ])[((zero_index + ((photo_index - 1) % least(photo_count, 4))) % 4) + 1] as photo_url
           ) selected_photo
         ), '[]'::jsonb) as legacy_photos,
         coalesce((
           select jsonb_agg(photo_url order by preview_index)
           from generate_series(1, least(photo_count, 4)) preview_index
           cross join lateral (
             select (array[
               'https://images.unsplash.com/photo-1494526585095-c41746248156?q=80&w=900&auto=format&fit=crop',
               'https://images.unsplash.com/photo-1518780664697-55e3ad937233?q=80&w=900&auto=format&fit=crop',
               'https://images.unsplash.com/photo-1564013799919-ab600027ffc6?q=80&w=900&auto=format&fit=crop',
               'https://images.unsplash.com/photo-1570129477492-45c003edd2be?q=80&w=900&auto=format&fit=crop'
             ])[((zero_index + preview_index - 1) % 4) + 1] as photo_url
           ) selected_preview
         ), '[]'::jsonb) as preview_photo_urls
  from enriched
)
insert into old_panel_cabin_seed (
  global_index,local_index,legacy_id,legacy_warehouse_id,id,warehouse_id,
  display_number,legacy_number,status,rental_type,dimensions,finishing,category,
  characteristics,linoleum,general_comment,shipment_date,tenant,price,photo_count,
  passport,created_at)
select global_index,local_index,legacy_id,legacy_warehouse_id,id,warehouse_id,
       display_number,legacy_number,status,rental_type,dimensions,finishing,category,
       characteristics,linoleum,general_comment,shipment_date,tenant,price,photo_count,
       jsonb_build_object(
         'source', 'old-panel-rental-items-v1',
         'legacyId', legacy_id,
         'legacyWarehouseId', legacy_warehouse_id,
         'legacyNumber', legacy_number,
         'locationNodeId', null,
         'shipmentDate', shipment_date,
         'tenant', tenant,
         'price', price,
         'hasPhotos', photo_count > 0,
         'photoCount', photo_count,
         'mainPhotoUrl', preview_photo_urls->0,
         'previewPhotoUrls', preview_photo_urls,
         'legacyPhotos', legacy_photos),
       created_at
from media;

insert into rental_item (
  id,version,warehouse_id,display_canonical_number,identity_match_key,status,
  rental_type,dimensions,finishing,category,characteristics,linoleum,general_comment,
  passport_json,tags_json,created_at,updated_at)
select id,
       case when general_comment is null then 1 else 2 end,
       warehouse_id,
       display_number,
       upper(regexp_replace(display_number, '[ -]', '', 'g')),
       status,
       rental_type,
       dimensions,
       finishing,
       category,
       characteristics,
       linoleum,
       general_comment,
       passport::text,
       '[]',
       created_at,
       created_at + case when general_comment is null then interval '1 second' else interval '2 seconds' end
from old_panel_cabin_seed;

create temporary table old_panel_equipment_seed (
  sequence_no integer primary key,
  id uuid not null unique,
  code varchar(64) not null unique,
  name varchar(255) not null unique,
  category varchar(32) not null
) on commit drop;

insert into old_panel_equipment_seed(sequence_no,id,code,name,category) values
  (1,'52000000-0000-4000-8000-000000000001','TABLE','Стол','FURNITURE'),
  (2,'52000000-0000-4000-8000-000000000002','OFFICE_TABLE','Стол офисный','FURNITURE'),
  (3,'52000000-0000-4000-8000-000000000003','BENCH','Лавка','FURNITURE'),
  (4,'52000000-0000-4000-8000-000000000004','CHAIR','Стул','FURNITURE'),
  (5,'52000000-0000-4000-8000-000000000005','BED','Кровать','FURNITURE'),
  (6,'52000000-0000-4000-8000-000000000006','BUNK_BED','Кровать 2-ярусная','FURNITURE'),
  (7,'52000000-0000-4000-8000-000000000007','WARDROBE','Шкаф','FURNITURE'),
  (8,'52000000-0000-4000-8000-000000000008','CONVECTOR','Конвектор','ELECTRICAL'),
  (9,'52000000-0000-4000-8000-000000000009','AIR_CONDITIONER','Кондиционер','ELECTRICAL');

insert into equipment_catalog_item (
  id,version,code,name,category,active,comment,created_at,updated_at)
select id,0,code,name,category,true,null,
       timestamptz '2026-07-19 00:30:00+03' + sequence_no * interval '1 second',
       timestamptz '2026-07-19 00:30:00+03' + sequence_no * interval '1 second'
from old_panel_equipment_seed;

create temporary table old_panel_balance_seed (
  sequence_no integer primary key,
  id uuid not null unique,
  equipment_id uuid not null,
  warehouse_id uuid not null,
  rental_item_id uuid,
  location_kind varchar(32) not null,
  quantity bigint not null,
  created_at timestamptz not null
) on commit drop;

with warehouse_values(legacy_id,warehouse_id,equipment_name,stock,written_off,lost) as (values
  ('spb-table','00000000-0000-0000-0000-000000000001'::uuid,'Стол',0,2,1),
  ('spb-office-table','00000000-0000-0000-0000-000000000001'::uuid,'Стол офисный',1,1,0),
  ('spb-bench','00000000-0000-0000-0000-000000000001'::uuid,'Лавка',20,0,2),
  ('spb-chair','00000000-0000-0000-0000-000000000001'::uuid,'Стул',0,5,3),
  ('spb-bed','00000000-0000-0000-0000-000000000001'::uuid,'Кровать',24,1,1),
  ('spb-bunk-bed','00000000-0000-0000-0000-000000000001'::uuid,'Кровать 2-ярусная',0,2,0),
  ('spb-wardrobe','00000000-0000-0000-0000-000000000001'::uuid,'Шкаф',0,0,0),
  ('spb-convector','00000000-0000-0000-0000-000000000001'::uuid,'Конвектор',24,3,2),
  ('spb-conditioner','00000000-0000-0000-0000-000000000001'::uuid,'Кондиционер',11,1,0),
  ('msk-table','00000000-0000-0000-0000-000000000002'::uuid,'Стол',12,1,0),
  ('msk-office-table','00000000-0000-0000-0000-000000000002'::uuid,'Стол офисный',6,0,1),
  ('msk-chair','00000000-0000-0000-0000-000000000002'::uuid,'Стул',20,2,1),
  ('msk-bench','00000000-0000-0000-0000-000000000002'::uuid,'Лавка',8,0,1),
  ('msk-bed','00000000-0000-0000-0000-000000000002'::uuid,'Кровать',10,1,2),
  ('msk-bunk-bed','00000000-0000-0000-0000-000000000002'::uuid,'Кровать 2-ярусная',5,1,0),
  ('msk-wardrobe','00000000-0000-0000-0000-000000000002'::uuid,'Шкаф',7,0,0)
), expanded as (
  select row_number() over (order by legacy_id, location_kind)::integer as sequence_no,
         warehouse_id,equipment_name,location_kind,quantity
  from warehouse_values
  cross join lateral (values
    ('STOCK'::varchar,stock::bigint),
    ('WRITTEN_OFF'::varchar,written_off::bigint),
    ('LOST'::varchar,lost::bigint)
  ) buckets(location_kind,quantity)
)
insert into old_panel_balance_seed(sequence_no,id,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at)
select expanded.sequence_no,
       ('53000000-0000-4000-8000-' || lpad(expanded.sequence_no::text,12,'0'))::uuid,
       equipment.id,
       expanded.warehouse_id,
       null,
       expanded.location_kind,
       expanded.quantity,
       timestamptz '2026-07-19 00:31:00+03' + expanded.sequence_no * interval '1 second'
from expanded
join old_panel_equipment_seed equipment on equipment.name=expanded.equipment_name;

with content as (
  select cabin.global_index,cabin.id as rental_item_id,cabin.warehouse_id,cabin.status,
         values_by_cabin.equipment_name,values_by_cabin.quantity
  from old_panel_cabin_seed cabin
  cross join lateral (
    select 'Стол'::varchar as equipment_name,2::bigint as quantity where (cabin.local_index - 1) % 4 = 0
    union all select 'Стул',4 where (cabin.local_index - 1) % 4 = 0
    union all select 'Шкаф',1 where (cabin.local_index - 1) % 4 = 0
    union all select 'Кровать 2-ярусная',3
      where (cabin.local_index - 1) % 4 <> 0 and (cabin.local_index - 1) % 6 = 0
    union all select 'Стол офисный',2
      where (cabin.local_index - 1) % 4 <> 0 and (cabin.local_index - 1) % 6 = 0
    union all select 'Кровать',2
      where (cabin.local_index - 1) % 4 <> 0 and (cabin.local_index - 1) % 6 <> 0
        and (cabin.local_index - 1) % 9 = 0
    union all select 'Стол офисный',2
      where (cabin.local_index - 1) % 4 <> 0 and (cabin.local_index - 1) % 6 <> 0
        and (cabin.local_index - 1) % 9 = 0
    union all select 'Конвектор',1
      where (cabin.local_index - 1) % 4 <> 0 and (cabin.local_index - 1) % 6 <> 0
        and (cabin.local_index - 1) % 9 = 0
  ) values_by_cabin
), numbered as (
  select 1000 + row_number() over (order by global_index,equipment_name)::integer as sequence_no,
         content.*
  from content
)
insert into old_panel_balance_seed(sequence_no,id,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at)
select numbered.sequence_no,
       ('53000000-0000-4000-8000-' || lpad(numbered.sequence_no::text,12,'0'))::uuid,
       equipment.id,
       numbered.warehouse_id,
       numbered.rental_item_id,
       case when numbered.status='RENTED' then 'CABIN_RENTED' else 'CABIN_NON_RENTED' end,
       numbered.quantity,
       timestamptz '2026-07-19 00:40:00+03' + numbered.sequence_no * interval '1 second'
from numbered
join old_panel_equipment_seed equipment on equipment.name=numbered.equipment_name;

insert into equipment_balance(
  id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,updated_at)
select id,0,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,created_at
from old_panel_balance_seed;

create temporary table old_panel_event_seed (
  event_id uuid not null primary key,
  aggregate_type varchar(64) not null,
  aggregate_id uuid not null,
  aggregate_version bigint not null,
  event_type varchar(160) not null,
  topic varchar(192) not null,
  payload jsonb not null,
  snapshot jsonb not null,
  recorded_at timestamptz not null,
  unique(aggregate_type,aggregate_id,aggregate_version)
) on commit drop;

-- Rental-item creation facts retain the real initial NEW state, followed by the
-- transferred status and, where present, the transferred general comment.
insert into old_panel_event_seed
select gen_random_uuid(),'RENTAL_ITEM',id,0,'asset.rental-item.created.v1','rwms.asset.rental-item.v1',
       jsonb_build_object(
         'rentalItemId',id::text,'warehouseId',warehouse_id::text,'status','NEW',
         'numberSha256',encode(sha256(convert_to(display_number,'UTF8')),'hex')),
       jsonb_build_object(
         'rentalItemId',id::text,'version',0,'warehouseId',warehouse_id::text,
         'number',display_number,'status','NEW','rentalType',rental_type,
         'dimensions',dimensions,'finishing',finishing,'category',category,
         'characteristics',characteristics,'linoleum',linoleum,'generalComment',null,
         'passport',passport,'tags','[]'::jsonb),
       created_at
from old_panel_cabin_seed;

insert into old_panel_event_seed
select gen_random_uuid(),'RENTAL_ITEM',id,1,'asset.rental-item.status-changed.v1','rwms.asset.rental-item.v1',
       jsonb_build_object(
         'rentalItemId',id::text,'warehouseId',warehouse_id::text,'status',status,
         'numberSha256',encode(sha256(convert_to(display_number,'UTF8')),'hex')),
       jsonb_build_object(
         'rentalItemId',id::text,'version',1,'warehouseId',warehouse_id::text,
         'number',display_number,'status',status,'rentalType',rental_type,
         'dimensions',dimensions,'finishing',finishing,'category',category,
         'characteristics',characteristics,'linoleum',linoleum,'generalComment',null,
         'passport',passport,'tags','[]'::jsonb),
       created_at + interval '1 second'
from old_panel_cabin_seed;

insert into old_panel_event_seed
select gen_random_uuid(),'RENTAL_ITEM',id,2,'asset.rental-item.general-comment-changed.v1','rwms.asset.rental-item.v1',
       jsonb_build_object('rentalItemId',id::text,'commentRevision',2),
       jsonb_build_object(
         'rentalItemId',id::text,'version',2,'warehouseId',warehouse_id::text,
         'number',display_number,'status',status,'rentalType',rental_type,
         'dimensions',dimensions,'finishing',finishing,'category',category,
         'characteristics',characteristics,'linoleum',linoleum,'generalComment',general_comment,
         'passport',passport,'tags','[]'::jsonb),
       created_at + interval '2 seconds'
from old_panel_cabin_seed
where general_comment is not null;

insert into old_panel_event_seed
select gen_random_uuid(),'EQUIPMENT_CATALOG',equipment.id,0,
       'asset.equipment-catalog.created.v1','rwms.asset.equipment-catalog.v1',
       jsonb_build_object(
         'equipmentId',equipment.id::text,'code',equipment.code,
         'category',equipment.category,'active',true),
       jsonb_build_object(
         'equipmentId',equipment.id::text,'version',0,'code',equipment.code,
         'name',equipment.name,'category',equipment.category,'active',true,'comment',null),
       catalog.created_at
from old_panel_equipment_seed equipment
join equipment_catalog_item catalog on catalog.id=equipment.id;

insert into old_panel_event_seed
select gen_random_uuid(),'EQUIPMENT_BALANCE',id,0,
       'asset.equipment-balance.changed.v1','rwms.asset.equipment-balance.v1',
       jsonb_build_object(
         'balanceId',id::text,'equipmentId',equipment_id::text,
         'warehouseId',warehouse_id::text,'rentalItemId',rental_item_id::text,
         'locationKind',location_kind,'quantity',quantity),
       jsonb_build_object(
         'balanceId',id::text,'equipmentId',equipment_id::text,
         'warehouseId',warehouse_id::text,'rentalItemId',rental_item_id::text,
         'locationKind',location_kind,'quantity',quantity),
       created_at
from old_panel_balance_seed;

insert into event_stream_head(aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
select distinct on (aggregate_type,aggregate_id)
       aggregate_type,aggregate_id::text,aggregate_version,event_id,recorded_at
from old_panel_event_seed
order by aggregate_type,aggregate_id,aggregate_version desc;

insert into domain_event(
  event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
  occurred_at,recorded_at,correlation_id,causation_id,actor_ref,payload,payload_sha256,baseline)
select event_id,aggregate_type,aggregate_id::text,aggregate_version,event_type,1,
       recorded_at,recorded_at,event_id,null,null,payload,
       encode(sha256(convert_to(payload::text,'UTF8')),'hex'),false
from old_panel_event_seed;

insert into aggregate_snapshot(
  aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at)
select aggregate_type,aggregate_id::text,aggregate_version,snapshot,
       encode(sha256(convert_to(snapshot::text,'UTF8')),'hex'),recorded_at
from old_panel_event_seed;

insert into projection_checkpoint(
  projection_name,aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at)
select distinct on (aggregate_type,aggregate_id)
       'asset-live-v1',aggregate_type,aggregate_id::text,aggregate_version,
       encode(sha256(convert_to(snapshot::text,'UTF8')),'hex'),recorded_at
from old_panel_event_seed
order by aggregate_type,aggregate_id,aggregate_version desc;

with envelopes as (
  select event_seed.*,
         jsonb_build_object(
           'envelopeVersion',2,
           'eventId',event_id::text,
           'eventType',event_type,
           'eventVersion',1,
           'occurredAt',recorded_at,
           'recordedAt',recorded_at,
           'producer','asset-service',
           'aggregateType',aggregate_type,
           'aggregateId',aggregate_id::text,
           'aggregateVersion',aggregate_version,
           'correlation',jsonb_build_object('correlationId',event_id::text,'causationId',null),
           'actorRef',null,
           'payload',payload) as envelope
  from old_panel_event_seed event_seed
)
insert into outbox_event(
  event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
  envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,created_at)
select event_id,aggregate_type,aggregate_id::text,aggregate_version,event_type,topic,
       envelope,encode(sha256(convert_to(envelope::text,'UTF8')),'hex'),
       'PENDING',0,recorded_at,recorded_at
from envelopes;
