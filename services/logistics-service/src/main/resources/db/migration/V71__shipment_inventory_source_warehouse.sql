ALTER TABLE logistics_document_line
    ADD COLUMN inventory_source_warehouse_id uuid;

UPDATE logistics_document_line line
SET inventory_source_warehouse_id = document.warehouse_id
FROM logistics_document document
WHERE document.id = line.document_id
  AND line.inventory_source_warehouse_id IS NULL;

CREATE INDEX idx_logistics_document_line_inventory_source
    ON logistics_document_line (inventory_source_warehouse_id, document_id);

COMMENT ON COLUMN logistics_document_line.inventory_source_warehouse_id IS
    'Physical warehouse source frozen per line; logistics_document.warehouse_id remains the service warehouse.';
