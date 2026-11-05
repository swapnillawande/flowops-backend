package com.flowops.fulfillment;

import com.flowops.audit.SystemActor;
import com.flowops.inventory.service.ReservationService;
import com.flowops.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.OffsetDateTime;

/** Internal cross-tenant maintenance coordinator; no HTTP endpoint exposes its candidate scan. */
@Component
public class ReservationExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryWorker.class);
    private final ReservationService inventory;
    private final OrderService orders;
    private final Clock clock;
    private final int batchSize;

    public ReservationExpiryWorker(ReservationService inventory, OrderService orders, Clock clock,
            @Value("${flowops.reservations.expiry-batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Expiry batch size must be 1–1000");
        this.inventory = inventory; this.orders = orders; this.clock = clock; this.batchSize = batchSize;
    }

    public int runBatch() {
        int expired = 0;
        // The scan holds no write locks. Each service call below owns a separate transaction and
        // rechecks state/deadline under its locks, so stale candidates and duplicate workers are safe.
        for (var candidate : inventory.findDue(OffsetDateTime.now(clock), batchSize)) {
            try (var actor = SystemActor.reservationExpiry()) {
                boolean changed = candidate.ownerOrderId() == null
                        ? inventory.expireStandalone(candidate.tenantId(), candidate.warehouseId(), candidate.id())
                        : orders.expireReservation(candidate.tenantId(), candidate.ownerOrderId(), candidate.id());
                if (changed) expired++;
            } catch (RuntimeException failure) {
                // One bad record must not roll back other candidates. Log identifiers, not customer
                // data or exception messages; the failed hold stays active for a later retry.
                log.error("Reservation expiry failed: tenantId={} reservationId={} exceptionType={}",
                        candidate.tenantId(), candidate.id(), failure.getClass().getSimpleName());
            }
        }
        if (expired > 0) log.info("Reservation expiry batch committed: expired={}", expired);
        return expired;
    }
}
