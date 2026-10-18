package com.flowops.inventory.exception;

public class DuplicateStockException extends RuntimeException {
    public DuplicateStockException() {
        super("Stock already exists for this product and warehouse");
    }
}
