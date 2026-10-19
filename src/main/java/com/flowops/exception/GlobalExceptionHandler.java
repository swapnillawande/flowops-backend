
package com.flowops.exception;

import com.flowops.order.exception.OrderNotFoundException;
import com.flowops.order.exception.OrderConflictException;
import com.flowops.inventory.exception.ReservationNotFoundException;
import com.flowops.inventory.exception.ReservationConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Maps expected business failures to stable HTTP errors without exposing database exception details. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({WarehouseNotFoundException.class, ProductNotFoundException.class, StockNotFoundException.class, ReservationNotFoundException.class, OrderNotFoundException.class})
    public ResponseEntity<Map<String, String>> handleNotFound(
            RuntimeException ex) {
        log.debug("Request rejected: exceptionType={}", ex.getClass().getSimpleName());

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
    
    
	@ExceptionHandler({DuplicateWarehouseException.class, DuplicateProductException.class, DuplicateStockException.class, StockMovementConflictException.class, ReservationConflictException.class, OrderConflictException.class})
	public ResponseEntity<Map<String, String>> handleDuplicate(RuntimeException ex) {
        log.debug("Request rejected: exceptionType={}", ex.getClass().getSimpleName());

		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Do not log the exception text: SQL constraint messages can contain submitted values.
        log.warn("Database constraint rejected a request: exceptionType={}", ex.getClass().getSimpleName());

		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("error", "The request conflicts with an existing database record"));
	}

}






