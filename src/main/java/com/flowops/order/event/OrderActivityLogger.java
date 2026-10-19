package com.flowops.order.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Default phase is AFTER_COMMIT: rolled-back orders never generate a success log. */
@Component
public class OrderActivityLogger {
    private static final Logger log = LoggerFactory.getLogger(OrderActivityLogger.class);
    @TransactionalEventListener
    public void committed(OrderActivity activity) {
        log.info("Order operation committed: operation={} tenantId={} orderId={}",
                activity.operation(), activity.tenantId(), activity.orderId());
    }
}
