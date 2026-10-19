-- State constraints evolve in a new migration; existing migrations remain unchanged.
ALTER TABLE customer_orders ADD COLUMN dispatched_at TIMESTAMPTZ;
ALTER TABLE customer_orders DROP CONSTRAINT chk_order_state;
ALTER TABLE customer_orders ADD CONSTRAINT chk_order_state CHECK (
    (status = 'PENDING' AND reservation_id IS NULL AND cancelled_at IS NULL AND dispatched_at IS NULL)
    OR (status = 'CONFIRMED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NULL)
    OR (status = 'CANCELLED' AND reservation_id IS NOT NULL AND cancelled_at IS NOT NULL AND dispatched_at IS NULL)
    OR (status = 'DISPATCHED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NOT NULL)
);

ALTER TABLE inventory_reservations ADD COLUMN consumed_at TIMESTAMPTZ;
ALTER TABLE inventory_reservations ADD COLUMN consumed_shipment_id UUID;
ALTER TABLE inventory_reservations DROP CONSTRAINT chk_reservation_status;
ALTER TABLE inventory_reservations ADD CONSTRAINT chk_reservation_status CHECK (
    (status = 'ACTIVE' AND released_at IS NULL AND consumed_at IS NULL AND consumed_shipment_id IS NULL)
    OR (status = 'RELEASED' AND released_at IS NOT NULL AND consumed_at IS NULL AND consumed_shipment_id IS NULL)
    OR (status = 'CONSUMED' AND released_at IS NULL AND consumed_at IS NOT NULL
        AND consumed_shipment_id IS NOT NULL AND owner_order_id IS NOT NULL)
);

-- Dispatch uses its own key, so caller-supplied receipt retry IDs cannot collide with it.
ALTER TABLE stock_movements ADD COLUMN shipment_id UUID;
ALTER TABLE stock_movements ADD CONSTRAINT uq_stock_dispatch UNIQUE (tenant_id, stock_id, shipment_id);
ALTER TABLE stock_movements DROP CONSTRAINT chk_movement_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_movement_type CHECK (type IN ('OPENING', 'RECEIPT', 'ADJUSTMENT', 'DISPATCH'));
ALTER TABLE stock_movements DROP CONSTRAINT chk_movement_quantity;
ALTER TABLE stock_movements ADD CONSTRAINT chk_movement_quantity CHECK (
    (type = 'OPENING' AND quantity >= 0 AND on_hand_before = 0 AND request_id IS NULL AND shipment_id IS NULL)
    OR (type = 'RECEIPT' AND quantity > 0 AND request_id IS NOT NULL AND shipment_id IS NULL)
    OR (type = 'ADJUSTMENT' AND quantity <> 0 AND request_id IS NOT NULL AND shipment_id IS NULL)
    OR (type = 'DISPATCH' AND quantity < 0 AND request_id IS NULL AND shipment_id IS NOT NULL)
);

CREATE TABLE shipments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    tracking_number VARCHAR(40) NOT NULL,
    carrier VARCHAR(80) NOT NULL CHECK (length(trim(carrier)) > 0),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT uq_shipment_order UNIQUE (tenant_id, order_id),
    CONSTRAINT uq_shipment_tracking UNIQUE (tenant_id, tracking_number),
    CONSTRAINT chk_shipment_state CHECK (
        (status = 'PREPARED' AND dispatched_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'DISPATCHED' AND dispatched_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND dispatched_at IS NULL AND cancelled_at IS NOT NULL))
);
CREATE INDEX idx_shipments_tenant_created ON shipments (tenant_id, created_at DESC, id);
