package com.flowops.tenant;

import java.util.UUID;

/** Test fixture only; production tenant identity always comes from a verified JWT. */
public final class DemoTenant {
    public static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private DemoTenant() {}
}
