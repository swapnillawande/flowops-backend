-- A reservation holds units for one warehouse without removing physical stock.
CREATE TABLE inventory_reservations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    request_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    CONSTRAINT uq_reservation_request UNIQUE (tenant_id, warehouse_id, request_id),
    CONSTRAINT uq_reservation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT chk_reservation_status CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL))
);

CREATE TABLE inventory_reservation_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    CONSTRAINT fk_reservation_item FOREIGN KEY (tenant_id, reservation_id)
        REFERENCES inventory_reservations (tenant_id, id),
    CONSTRAINT uq_reservation_product UNIQUE (tenant_id, reservation_id, product_id)
);
