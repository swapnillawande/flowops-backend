package com.flowops.order.exception;

/** The caller reused a request ID with different order details. */
public class OrderConflictException extends RuntimeException {
    public OrderConflictException() { super("Request ID was already used with different order details"); }
}
