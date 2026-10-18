package com.flowops.tenant;

import java.util.UUID;

/** Local demo tenant until verified identity claims supply the tenant. */
public final class DemoTenant {
    public static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private DemoTenant() {}
}
