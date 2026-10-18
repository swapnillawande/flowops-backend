
package com.flowops.exception;

import com.flowops.inventory.exception.StockMovementConflictException;
import com.flowops.inventory.exception.DuplicateStockException;
import com.flowops.inventory.exception.StockNotFoundException;
import com.flowops.product.exception.DuplicateProductException;
import com.flowops.product.exception.ProductNotFoundException;
import com.flowops.warehouse.exception.DuplicateWarehouseException;
import com.flowops.warehouse.exception.WarehouseNotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({WarehouseNotFoundException.class, ProductNotFoundException.class, StockNotFoundException.class})
    public ResponseEntity<Map<String, String>> handleNotFound(
            RuntimeException ex) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(
            MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Invalid request");

        return ResponseEntity.badRequest()
                .body(Map.of("error", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleMalformedRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Malformed request body or invalid parameter"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(
            IllegalArgumentException ex) {

        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getMessage()));
    }
    
    
	@ExceptionHandler({DuplicateWarehouseException.class, DuplicateProductException.class, DuplicateStockException.class, StockMovementConflictException.class})
	public ResponseEntity<Map<String, String>> handleDuplicate(RuntimeException ex) {

		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {

		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("error", "The request conflicts with an existing database record"));
	}

}






