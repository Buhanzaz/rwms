CREATE TABLE contractor_company (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    warehouse_id UUID NOT NULL,
    name VARCHAR(256) NOT NULL,
    inn VARCHAR(12) NOT NULL CHECK (inn ~ '^([0-9]{10}|[0-9]{12})$'),
    contact_name VARCHAR(256),
    phone VARCHAR(64) NOT NULL,
    email VARCHAR(256),
    address VARCHAR(1000),
    comment_text VARCHAR(2000),
    CONSTRAINT uk_contractor_company_inn UNIQUE (warehouse_id, inn),
    CONSTRAINT uk_contractor_company_owner UNIQUE (id, warehouse_id)
);

ALTER TABLE worker
    ADD COLUMN contractor_company_id UUID,
    ADD CONSTRAINT fk_worker_contractor_company_owner
        FOREIGN KEY (contractor_company_id, warehouse_id)
        REFERENCES contractor_company (id, warehouse_id),
    ADD CONSTRAINT ck_worker_contractor_company_employment
        CHECK (contractor_company_id IS NULL OR employment_type = 'CONTRACTOR');

CREATE INDEX idx_worker_contractor_company ON worker (contractor_company_id)
    WHERE contractor_company_id IS NOT NULL;
