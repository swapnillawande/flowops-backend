package com.flowops.inventory.exception;

public class StockMovementConflictException extends RuntimeException {
    public StockMovementConflictException(String message) {
        super(message);
    }
}
