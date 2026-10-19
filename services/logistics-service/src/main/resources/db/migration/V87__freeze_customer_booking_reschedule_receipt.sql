ALTER TABLE public.customer_booking_mutation
    ADD COLUMN reschedule_result_json jsonb,
    ADD CONSTRAINT ck_customer_booking_mutation_reschedule_result CHECK (
        reschedule_result_json IS NULL
        OR jsonb_typeof(reschedule_result_json) = 'object'
    );
