
CREATE UNIQUE INDEX uq_warehouses_tenant_name
ON warehouses (tenant_id, LOWER(name));
