package com.flowops.order.exception;

/** Order command conflicts with an earlier request or a lifecycle state. */
public class OrderConflictException extends RuntimeException {
    public OrderConflictException(String message) { super(message); }
    public OrderConflictException() { super("Request ID was already used with different order details"); }
}
