package com.flowops.order.exception;

/** Unknown and out-of-tenant IDs share the same safe HTTP response. */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException() { super("Order not found"); }
}
