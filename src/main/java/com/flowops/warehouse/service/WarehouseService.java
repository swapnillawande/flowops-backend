
package com.flowops.warehouse.service;

import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.exception.DuplicateWarehouseException;
import com.flowops.warehouse.exception.WarehouseNotFoundException;
import com.flowops.warehouse.repository.WarehouseRepository;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Warehouse business rules and transaction boundaries; database uniqueness also protects concurrent creates. */
@Service
public class WarehouseService {
    private static final Logger log = LoggerFactory.getLogger(WarehouseService.class);

    private final WarehouseRepository warehouseRepository;

    public WarehouseService(WarehouseRepository warehouseRepository) {
        this.warehouseRepository = warehouseRepository;
    }

    @Transactional
    public Warehouse createWarehouse(
            UUID tenantId,
            String name,
            String city,
            String countryCode) {

        String normalizedName = name.trim();
        String normalizedCity = city.trim();
        String normalizedCountry = countryCode.trim().toUpperCase(Locale.ROOT);


		if (warehouseRepository.existsByTenantIdAndNameIgnoreCase(
		        tenantId, normalizedName)) {
		
		    throw new DuplicateWarehouseException(
		            "Warehouse name already exists for this tenant: "
		                    + normalizedName
		    );
		}


        Warehouse warehouse = new Warehouse(
                tenantId,
                normalizedName,
                normalizedCity,
                normalizedCountry
        );

        log.debug("Warehouse creation prepared: tenantId={} warehouseId={}", tenantId, warehouse.getId());
        return warehouseRepository.save(warehouse);
    }

    @Transactional(readOnly = true)
    public List<Warehouse> getWarehouses(UUID tenantId) {
        return warehouseRepository.findByTenantId(tenantId);
    }


	@Transactional(readOnly = true)
	public Warehouse getWarehouseById(UUID tenantId, UUID id) {
		
	    return warehouseRepository.findByIdAndTenantId(id, tenantId)
	            .orElseThrow(() ->
	                new WarehouseNotFoundException(
	                    "Warehouse not found: " + id
	                )
	            );
	}

}
