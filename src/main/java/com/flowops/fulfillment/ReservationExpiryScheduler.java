package com.flowops.fulfillment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fixed delay prevents overlapping runs in one process; row locks protect multiple processes. */
@Component
@ConditionalOnProperty(name = "flowops.reservations.expiry-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);
    private final ReservationExpiryWorker worker;
    public ReservationExpiryScheduler(ReservationExpiryWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${flowops.reservations.expiry-interval:PT1M}",
            initialDelayString = "${flowops.reservations.expiry-interval:PT1M}")
    public void expireDueHolds() {
        try { worker.runBatch(); }
        catch (RuntimeException failure) {
            log.error("Reservation expiry scan failed: exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
