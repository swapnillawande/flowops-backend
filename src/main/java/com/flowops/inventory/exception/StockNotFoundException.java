package com.flowops.inventory.exception;

public class StockNotFoundException extends RuntimeException {
    public StockNotFoundException() {
        super("Stock not found for this product and warehouse");
    }
}
