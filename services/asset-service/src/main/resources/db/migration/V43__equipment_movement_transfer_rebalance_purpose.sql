-- Persist the closed movement purpose so execution can enforce that loose
-- inter-warehouse furniture remains a STOCK-to-STOCK transfer.

ALTER TABLE public.equipment_allocation_hold
  ADD COLUMN movement_purpose varchar(32);

UPDATE public.equipment_allocation_hold
SET movement_purpose = CASE owner_type
  WHEN 'LOGISTICS_EQUIPMENT_MOVEMENT' THEN 'ALLOCATABLE_REBALANCE'
  WHEN 'MAINTENANCE_DISPOSITION_MOVEMENT' THEN 'MAINTENANCE_DISPOSITION'
  ELSE NULL
END;

ALTER TABLE public.equipment_allocation_hold
  ADD CONSTRAINT ck_equipment_hold_movement_purpose CHECK (
    (owner_type = 'LOGISTICS_EQUIPMENT_MOVEMENT'
      AND movement_purpose IS NOT NULL
      AND movement_purpose IN ('ALLOCATABLE_REBALANCE','TRANSFER_REBALANCE'))
    OR (owner_type = 'MAINTENANCE_DISPOSITION_MOVEMENT'
      AND movement_purpose IS NOT NULL
      AND movement_purpose = 'MAINTENANCE_DISPOSITION')
    OR (owner_type NOT IN (
        'LOGISTICS_EQUIPMENT_MOVEMENT',
        'MAINTENANCE_DISPOSITION_MOVEMENT')
      AND movement_purpose IS NULL)
  );
