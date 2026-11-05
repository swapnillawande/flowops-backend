package com.flowops.inventory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** Configuration applies to new holds only; changing it never rewrites an existing deadline. */
@Component
public class ReservationPolicy {
    private final Duration holdTtl;
    public ReservationPolicy(@Value("${flowops.reservations.hold-ttl:PT30M}") Duration holdTtl) {
        if (holdTtl.compareTo(Duration.ofSeconds(1)) < 0 || holdTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Reservation hold TTL must be between one second and seven days");
        }
        this.holdTtl = holdTtl;
    }
    public Duration holdTtl() { return holdTtl; }
}
