CREATE TABLE stock (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    product_id UUID NOT NULL,
    on_hand INTEGER NOT NULL,
    reserved INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_stock_location UNIQUE (tenant_id, warehouse_id, product_id),
    CONSTRAINT chk_stock_nonnegative CHECK (on_hand >= 0 AND reserved >= 0),
    CONSTRAINT chk_stock_available CHECK (reserved <= on_hand)
);
