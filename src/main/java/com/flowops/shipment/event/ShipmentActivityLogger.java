package com.flowops.shipment.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Defaults to AFTER_COMMIT so rolled-back dispatches never look successful in logs. */
@Component
public class ShipmentActivityLogger {
    private static final Logger log = LoggerFactory.getLogger(ShipmentActivityLogger.class);
    @TransactionalEventListener
    public void committed(ShipmentActivity activity) {
        log.info("Shipment operation committed: operation={} tenantId={} shipmentId={} orderId={}",
                activity.operation(), activity.tenantId(), activity.shipmentId(), activity.orderId());
    }
}
