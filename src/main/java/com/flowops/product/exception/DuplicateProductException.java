package com.flowops.product.exception;

public class DuplicateProductException extends RuntimeException {
    public DuplicateProductException(String sku) {
        super("Product SKU already exists for this tenant: " + sku);
    }
}
