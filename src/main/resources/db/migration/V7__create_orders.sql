-- Ownership prevents a standalone reservation command from releasing an order's hold.
ALTER TABLE inventory_reservations ADD COLUMN owner_order_id UUID;
ALTER TABLE inventory_reservations ADD CONSTRAINT uq_reservation_owner UNIQUE (tenant_id, owner_order_id);

CREATE TABLE customer_orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    customer_reference VARCHAR(100) NOT NULL CHECK (length(trim(customer_reference)) > 0),
    status VARCHAR(20) NOT NULL,
    reservation_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT uq_order_request UNIQUE (tenant_id, request_id),
    CONSTRAINT uq_order_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_order_reservation UNIQUE (tenant_id, reservation_id),
    CONSTRAINT chk_order_state CHECK (
        (status = 'PENDING' AND reservation_id IS NULL AND cancelled_at IS NULL)
        OR (status = 'CONFIRMED' AND reservation_id IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND reservation_id IS NOT NULL AND cancelled_at IS NOT NULL))
);
CREATE INDEX idx_orders_tenant_created ON customer_orders (tenant_id, created_at DESC, id);

CREATE TABLE customer_order_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(150) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    CONSTRAINT fk_item_order FOREIGN KEY (tenant_id, order_id) REFERENCES customer_orders (tenant_id, id),
    CONSTRAINT uq_order_product UNIQUE (tenant_id, order_id, product_id)
);
