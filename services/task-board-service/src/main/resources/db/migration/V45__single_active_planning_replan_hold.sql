CREATE UNIQUE INDEX uk_planning_replan_hold_active_source
    ON public.planning_replan_hold (source_plan_id)
    WHERE state = 'PREPARED';
