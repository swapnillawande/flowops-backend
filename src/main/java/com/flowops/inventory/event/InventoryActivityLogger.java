package com.flowops.inventory.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Log successes after commit, so a rolled-back command never produces a misleading success log. */
@Component
public class InventoryActivityLogger {
    private static final Logger log = LoggerFactory.getLogger(InventoryActivityLogger.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void logCommittedActivity(InventoryActivity activity) {
        log.info("Inventory operation committed: operation={} tenantId={} warehouseId={} recordId={}",
                activity.operation(), activity.tenantId(), activity.warehouseId(), activity.recordId());
    }
}
