
package com.flowops.warehouse.service;

import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.exception.DuplicateWarehouseException;
import com.flowops.warehouse.repository.WarehouseRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

    @Mock
    private WarehouseRepository warehouseRepository;

    @InjectMocks
    private WarehouseService warehouseService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void shouldCreateWarehouseSuccessfully() {

        when(warehouseRepository.existsByTenantIdAndNameIgnoreCase(
                tenantId, "Berlin Central Warehouse"))
                .thenReturn(false);

        when(warehouseRepository.save(any(Warehouse.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Warehouse result = warehouseService.createWarehouse(
                tenantId,
                "Berlin Central Warehouse",
                "Berlin",
                "DE"
        );

        assertNotNull(result.getId());
        assertEquals("Berlin Central Warehouse", result.getName());
        assertEquals("Berlin", result.getCity());
        assertEquals("DE", result.getCountryCode());
        assertEquals(tenantId, result.getTenantId());

        verify(warehouseRepository).save(any(Warehouse.class));
    }

    @Test
    void shouldRejectDuplicateWarehouseName() {

        when(warehouseRepository.existsByTenantIdAndNameIgnoreCase(
                tenantId, "Berlin Central Warehouse"))
                .thenReturn(true);

        assertThrows(
                DuplicateWarehouseException.class,
                () -> warehouseService.createWarehouse(
                        tenantId,
                        "Berlin Central Warehouse",
                        "Berlin",
                        "DE"
                )
        );

        verify(warehouseRepository, never())
                .save(any(Warehouse.class));
    }
}
