package com.flowops.shipment.exception;

/** Missing and out-of-tenant shipments are indistinguishable to clients. */
public class ShipmentNotFoundException extends RuntimeException {
    public ShipmentNotFoundException() { super("Shipment not found"); }
}
