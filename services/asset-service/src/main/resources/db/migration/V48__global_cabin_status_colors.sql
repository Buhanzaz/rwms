create table cabin_status_colors (
    id uuid primary key check (id = '00000000-0000-0000-0000-000000000001'),
    version bigint not null default 0,
    colors jsonb not null check (jsonb_typeof(colors) = 'object'),
    updated_at timestamptz not null default now()
);

insert into cabin_status_colors (id, colors) values (
    '00000000-0000-0000-0000-000000000001',
    '{"RENTED":"#0891B2","BOOKED":"#8B5CF6","REPAIR":"#F97316","WAITING_REPAIR_CHECK":"#EAB308","WRITTEN_OFF":"#64748B","LOST":"#DC2626","CAPITAL_REPAIR":"#DC2626","AFTER_RENT":"#F59E0B","WAITING_ESTIMATE_CONFIRMATION":"#D97706","SALE":"#C026D3","USED_SALE":"#C026D3","RESERVED":"#8B5CF6","FREE":"#16A34A","WAREHOUSE":"#64748B","OWN_NEEDS":"#6366F1","IN_TRANSFER":"#0D9488"}'
);
