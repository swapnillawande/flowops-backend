package com.flowops.inventory.exception;

/** A valid command cannot be applied to the current balance or existing request. */
public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(String message) { super(message); }
}
