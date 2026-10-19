ALTER TABLE public.planning_published_reschedule_saga
    ADD COLUMN operation varchar(24) NOT NULL DEFAULT 'RESCHEDULE',
    ALTER COLUMN booking_id DROP NOT NULL,
    ALTER COLUMN customer_subject_id DROP NOT NULL,
    ADD CONSTRAINT ck_planning_published_recovery_operation CHECK (
        operation IN ('RESCHEDULE', 'CANCELLATION')
    ),
    ADD CONSTRAINT ck_planning_published_recovery_customer_context CHECK (
        operation <> 'RESCHEDULE'
        OR (booking_id IS NOT NULL AND customer_subject_id IS NOT NULL)
    );

ALTER TABLE public.planning_published_reschedule_saga
    ALTER COLUMN operation DROP DEFAULT;

CREATE UNIQUE INDEX uk_planning_published_recovery_active_source
    ON public.planning_published_reschedule_saga (source_plan_id)
    WHERE state NOT IN ('COMPLETE', 'RELEASED');
