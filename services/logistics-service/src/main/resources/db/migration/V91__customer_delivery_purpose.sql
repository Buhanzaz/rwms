ALTER TABLE logistics_document
  ADD COLUMN customer_delivery_purpose varchar(32);

UPDATE logistics_document
SET customer_delivery_purpose = 'RENTAL_DELIVERY'
WHERE document_type = 'SHIPMENT';

ALTER TABLE logistics_document
  ADD CONSTRAINT ck_logistics_document_customer_delivery_purpose
  CHECK (
    (document_type = 'SHIPMENT'
      AND customer_delivery_purpose IN (
        'RENTAL_DELIVERY',
        'SALE_DELIVERY',
        'CUSTOMER_RELOCATION'))
    OR
    (document_type <> 'SHIPMENT' AND customer_delivery_purpose IS NULL)
  );
