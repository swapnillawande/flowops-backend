ALTER TABLE customer_orders ADD COLUMN delivered_at TIMESTAMPTZ;
ALTER TABLE customer_orders DROP CONSTRAINT chk_order_state;
ALTER TABLE customer_orders ADD CONSTRAINT chk_order_state CHECK (
    (status = 'PENDING' AND reservation_id IS NULL AND cancelled_at IS NULL AND dispatched_at IS NULL AND delivered_at IS NULL)
    OR (status = 'CONFIRMED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NULL AND delivered_at IS NULL)
    OR (status = 'CANCELLED' AND reservation_id IS NOT NULL AND cancelled_at IS NOT NULL AND dispatched_at IS NULL AND delivered_at IS NULL)
    OR (status = 'DISPATCHED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NOT NULL AND delivered_at IS NULL)
    OR (status = 'DELIVERED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NOT NULL AND delivered_at IS NOT NULL)
);
ALTER TABLE shipments ADD COLUMN in_transit_at TIMESTAMPTZ;
ALTER TABLE shipments ADD COLUMN delivered_at TIMESTAMPTZ;
ALTER TABLE shipments ADD CONSTRAINT uq_shipment_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE shipments DROP CONSTRAINT chk_shipment_state;
ALTER TABLE shipments ADD CONSTRAINT chk_shipment_state CHECK (
    (status = 'PREPARED' AND dispatched_at IS NULL AND cancelled_at IS NULL AND in_transit_at IS NULL AND delivered_at IS NULL)
    OR (status = 'DISPATCHED' AND dispatched_at IS NOT NULL AND cancelled_at IS NULL AND in_transit_at IS NULL AND delivered_at IS NULL)
    OR (status = 'CANCELLED' AND dispatched_at IS NULL AND cancelled_at IS NOT NULL AND in_transit_at IS NULL AND delivered_at IS NULL)
    OR (status = 'IN_TRANSIT' AND dispatched_at IS NOT NULL AND cancelled_at IS NULL AND in_transit_at IS NOT NULL AND delivered_at IS NULL)
    OR (status = 'DELIVERED' AND dispatched_at IS NOT NULL AND cancelled_at IS NULL AND in_transit_at IS NOT NULL AND delivered_at IS NOT NULL)
);
CREATE TABLE shipment_status_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    request_id UUID,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PREPARED', 'DISPATCHED', 'CANCELLED', 'IN_TRANSIT', 'DELIVERED')),
    note VARCHAR(250) NOT NULL CHECK (length(trim(note)) > 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_status_shipment FOREIGN KEY (tenant_id, shipment_id) REFERENCES shipments (tenant_id, id),
    CONSTRAINT uq_shipment_sequence UNIQUE (tenant_id, shipment_id, sequence),
    CONSTRAINT uq_shipment_status_request UNIQUE (tenant_id, shipment_id, request_id)
);
-- Reconstruct only transitions supported by stored timestamps, without inventing carrier history.
INSERT INTO shipment_status_events (id, tenant_id, shipment_id, sequence, status, note, created_at)
SELECT gen_random_uuid(), tenant_id, id,
       row_number() OVER (PARTITION BY tenant_id, id ORDER BY step), status, 'Migrated existing shipment state', occurred_at
FROM (
    SELECT tenant_id, id, 1 AS step, 'PREPARED' AS status, created_at AS occurred_at FROM shipments
    UNION ALL SELECT tenant_id, id, 2, 'DISPATCHED', dispatched_at FROM shipments WHERE dispatched_at IS NOT NULL
    UNION ALL SELECT tenant_id, id, 2, 'CANCELLED', cancelled_at FROM shipments WHERE cancelled_at IS NOT NULL
) history;
