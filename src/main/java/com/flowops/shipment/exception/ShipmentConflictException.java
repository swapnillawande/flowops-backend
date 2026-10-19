package com.flowops.shipment.exception;

/** Valid shipment command conflicts with a lifecycle state or original creation details. */
public class ShipmentConflictException extends RuntimeException {
    public ShipmentConflictException(String message) { super(message); }
}
