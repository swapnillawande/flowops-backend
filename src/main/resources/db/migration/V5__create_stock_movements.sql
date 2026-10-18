ALTER TABLE stock ADD CONSTRAINT uq_stock_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE stock_movements (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    stock_id UUID NOT NULL,
    request_id UUID,
    type VARCHAR(20) NOT NULL,
    quantity INTEGER NOT NULL,
    on_hand_before INTEGER NOT NULL,
    on_hand_after INTEGER NOT NULL,
    reason VARCHAR(250) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_movement_stock FOREIGN KEY (tenant_id, stock_id) REFERENCES stock (tenant_id, id),
    CONSTRAINT uq_stock_movement_request UNIQUE (tenant_id, stock_id, request_id),
    CONSTRAINT chk_movement_type CHECK (type IN ('OPENING', 'RECEIPT', 'ADJUSTMENT')),
    CONSTRAINT chk_movement_balance CHECK (
        on_hand_before >= 0 AND on_hand_after >= 0
        AND on_hand_before::BIGINT + quantity::BIGINT = on_hand_after::BIGINT),
    CONSTRAINT chk_movement_quantity CHECK (
        (type = 'OPENING' AND quantity >= 0 AND on_hand_before = 0 AND request_id IS NULL)
        OR (type = 'RECEIPT' AND quantity > 0 AND request_id IS NOT NULL)
        OR (type = 'ADJUSTMENT' AND quantity <> 0 AND request_id IS NOT NULL)),
    CONSTRAINT chk_movement_reason CHECK (length(trim(reason)) > 0)
);

CREATE INDEX idx_stock_movements_history ON stock_movements (tenant_id, stock_id, created_at, id);

-- Preserve the baseline of stock initialized before the audit ledger existed.
INSERT INTO stock_movements (id, tenant_id, stock_id, request_id, type, quantity,
                             on_hand_before, on_hand_after, reason, created_at)
SELECT id, tenant_id, id, NULL, 'OPENING', on_hand, 0, on_hand,
       'Opening balance migrated from existing stock', created_at
FROM stock;
