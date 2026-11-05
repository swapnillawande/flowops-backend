-- Existing holds deliberately retain NULL deadlines: an upgrade must not cancel old orders.
ALTER TABLE inventory_reservations ADD COLUMN expires_at TIMESTAMPTZ, ADD COLUMN expired_at TIMESTAMPTZ;
ALTER TABLE inventory_reservations ADD CONSTRAINT chk_reservation_deadline
    CHECK (expires_at IS NULL OR expires_at > created_at);
ALTER TABLE inventory_reservations DROP CONSTRAINT chk_reservation_status;
ALTER TABLE inventory_reservations ADD CONSTRAINT chk_reservation_status CHECK (
    (status = 'ACTIVE' AND released_at IS NULL AND consumed_at IS NULL AND consumed_shipment_id IS NULL AND expired_at IS NULL)
    OR (status = 'RELEASED' AND released_at IS NOT NULL AND consumed_at IS NULL AND consumed_shipment_id IS NULL AND expired_at IS NULL)
    OR (status = 'CONSUMED' AND released_at IS NULL AND consumed_at IS NOT NULL AND consumed_shipment_id IS NOT NULL AND owner_order_id IS NOT NULL AND expired_at IS NULL)
    OR (status = 'EXPIRED' AND released_at IS NULL AND consumed_at IS NULL AND consumed_shipment_id IS NULL
        AND expires_at IS NOT NULL AND expired_at IS NOT NULL AND expired_at >= expires_at)
);
CREATE INDEX idx_active_reservation_expiry ON inventory_reservations (expires_at, id)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- Snapshot the owned hold's immutable deadline for order details/listing without cross-module queries.
ALTER TABLE customer_orders ADD COLUMN reservation_expires_at TIMESTAMPTZ, ADD COLUMN expired_at TIMESTAMPTZ;
ALTER TABLE customer_orders DROP CONSTRAINT chk_order_state;
ALTER TABLE customer_orders ADD CONSTRAINT chk_order_state CHECK (
    (status = 'PENDING' AND reservation_id IS NULL AND cancelled_at IS NULL AND dispatched_at IS NULL AND delivered_at IS NULL AND expired_at IS NULL AND reservation_expires_at IS NULL)
    OR (status = 'CONFIRMED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NULL AND delivered_at IS NULL AND expired_at IS NULL)
    OR (status = 'CANCELLED' AND reservation_id IS NOT NULL AND cancelled_at IS NOT NULL AND dispatched_at IS NULL AND delivered_at IS NULL AND expired_at IS NULL)
    OR (status = 'DISPATCHED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NOT NULL AND delivered_at IS NULL AND expired_at IS NULL)
    OR (status = 'DELIVERED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NOT NULL AND delivered_at IS NOT NULL AND expired_at IS NULL)
    OR (status = 'EXPIRED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL AND dispatched_at IS NULL AND delivered_at IS NULL
        AND reservation_expires_at IS NOT NULL AND expired_at IS NOT NULL AND expired_at >= reservation_expires_at)
);
