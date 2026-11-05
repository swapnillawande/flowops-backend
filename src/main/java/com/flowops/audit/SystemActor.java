package com.flowops.audit;

/** Explicit, thread-scoped attribution for the expiry job. No HTTP parameter can select this identity. */
public final class SystemActor implements AutoCloseable {
    private static final ThreadLocal<Boolean> EXPIRY = new ThreadLocal<>();
    private final Boolean previous;
    private SystemActor() {
        previous = EXPIRY.get();
        EXPIRY.set(true);
    }
    public static SystemActor reservationExpiry() { return new SystemActor(); }
    static boolean isReservationExpiry() { return Boolean.TRUE.equals(EXPIRY.get()); }
    @Override
    public void close() {
        // Restore nested scopes and remove scheduler-thread state even after a failed transaction.
        if (previous == null) EXPIRY.remove(); else EXPIRY.set(previous);
    }
}
