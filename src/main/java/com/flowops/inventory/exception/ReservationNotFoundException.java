package com.flowops.inventory.exception;

/** Missing and out-of-scope reservations share the same response to avoid exposing other tenants. */
public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException() { super("Reservation not found"); }
}
