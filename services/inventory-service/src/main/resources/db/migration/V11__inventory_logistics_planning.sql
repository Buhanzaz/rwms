ALTER TABLE finding_plan_snapshot
    ADD COLUMN logistics_planning_mode VARCHAR(16),
    ADD COLUMN logistics_scheduled_date DATE;

UPDATE finding_plan_snapshot
SET logistics_planning_mode = 'AUTO'
WHERE logistics_planning_mode IS NULL;

ALTER TABLE finding_plan_snapshot
    ALTER COLUMN logistics_planning_mode SET NOT NULL,
    ADD CONSTRAINT ck_finding_plan_snapshot_logistics_planning
        CHECK (
            (logistics_planning_mode = 'AUTO' AND logistics_scheduled_date IS NULL)
            OR
            (logistics_planning_mode = 'FIXED_DATE' AND logistics_scheduled_date IS NOT NULL)
        );
