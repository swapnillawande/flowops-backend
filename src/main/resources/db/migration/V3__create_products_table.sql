CREATE TABLE products (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(150) NOT NULL,
    weight_kg NUMERIC(9, 3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_products_tenant_sku UNIQUE (tenant_id, sku),
    CONSTRAINT chk_products_sku CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'),
    CONSTRAINT chk_products_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_products_weight_positive CHECK (weight_kg > 0)
);
