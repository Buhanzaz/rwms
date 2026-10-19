-- Separate task-entry read visibility from result-upload authorization. Existing
-- assignees keep their current read access; newer owner proofs may add workers
-- who can open the task without granting them evidence upload rights.
-- No media object, owner proof, or user row is deleted by this migration.

create table media_task_board_entry_reader_worker (
    entry_id uuid not null references media_task_board_entry_owner_proof (entry_id),
    worker_id uuid not null,
    primary key (entry_id,worker_id)
);

create index media_task_board_entry_reader_worker_access_idx
    on media_task_board_entry_reader_worker (worker_id,entry_id);

insert into media_task_board_entry_reader_worker (entry_id,worker_id)
select entry_id,worker_id
from media_task_board_entry_allowed_worker;
