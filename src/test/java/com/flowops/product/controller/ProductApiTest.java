package com.flowops.product.controller;

import com.flowops.product.entity.Product;
import com.flowops.product.repository.ProductRepository;
import com.flowops.support.PostgresIntegrationTest;
import com.flowops.tenant.DemoTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProductApiTest extends PostgresIntegrationTest {
    private static final String PRODUCTS = "/api/v1/products";

    @Autowired MockMvc mvc;
    @Autowired ProductRepository repository;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAllInBatch();
    }

    @Test
    void createsNormalizesAndRetrievesProduct() throws Exception {
        mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sku":" laptop-001 ","name":" Laptop ","weightKg":1.250}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("sku").value("LAPTOP-001"))
                .andExpect(jsonPath("name").value("Laptop"))
                .andExpect(jsonPath("weightKg").value(1.25))
                .andExpect(jsonPath("createdAt").exists())
                .andExpect(jsonPath("tenantId").doesNotExist())
                .andExpect(header().exists("Location"))
                .andExpect(result -> mvc.perform(get(result.getResponse().getHeader("Location")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("sku").value("LAPTOP-001"))
                        .andExpect(jsonPath("weightKg").value(1.25)));
        assertEquals(DemoTenant.ID, repository.findAll().getFirst().getTenantId());
    }

    @Test
    void listsOnlyCurrentTenantsProductsInSkuOrder() throws Exception {
        repository.saveAndFlush(product(DemoTenant.ID, "B-002"));
        repository.saveAndFlush(product(DemoTenant.ID, "A-001"));
        repository.saveAndFlush(product(UUID.randomUUID(), "PRIVATE"));
        mvc.perform(get(PRODUCTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sku").value("A-001"))
                .andExpect(jsonPath("$[1].sku").value("B-002"));
    }

    @Test
    void duplicateSkuIsNormalizedBeforeChecking() throws Exception {
        repository.saveAndFlush(product(DemoTenant.ID, "LAPTOP-001"));
        mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sku":" laptop-001 ","name":"Another laptop","weightKg":2}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("error").value("Product SKU already exists for this tenant: LAPTOP-001"));
        assertEquals(1, repository.count());
    }

    @Test
    void anotherTenantCanReuseSkuButCannotBeRead() throws Exception {
        Product other = repository.saveAndFlush(product(UUID.randomUUID(), "LAPTOP-001"));
        mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sku":"LAPTOP-001","name":"Laptop","weightKg":2}
                        """))
                .andExpect(status().isCreated());
        mvc.perform(get(PRODUCTS + "/" + other.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("error").isString());
    }

    @Test
    void unknownProductReturnsNotFound() throws Exception {
        mvc.perform(get(PRODUCTS + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingInvalidAndOversizedValuesWithoutWriting() throws Exception {
        for (String body : new String[]{
                "{}",
                body("", "Laptop", "1"),
                body("BAD SKU", "Laptop", "1"),
                body("-BAD", "Laptop", "1"),
                body("A".repeat(65), "Laptop", "1"),
                body("SKU", "   ", "1"),
                body("SKU", "N".repeat(151), "1"),
                body("SKU", "Laptop", "null"),
                body("SKU", "Laptop", "0"),
                body("SKU", "Laptop", "-1"),
                body("SKU", "Laptop", "0.0001"),
                body("SKU", "Laptop", "1000000")}) {
            mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("error").isString());
        }
        assertEquals(0, repository.count());
    }

    @Test
    void acceptsSupportedWeightLimits() throws Exception {
        for (String weight : new String[]{"0.001", "999999.999"}) {
            mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
                            .content(body("SKU-" + weight, "Valid weight", weight)))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void malformedRequestsReturnConsistentError() throws Exception {
        mvc.perform(get(PRODUCTS + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("error").value("Malformed request body or invalid parameter"));
        mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("error").value("Malformed request body or invalid parameter"));
    }

    @Test
    void databaseRejectsDuplicateSkuAndNonpositiveWeight() {
        repository.saveAndFlush(product(DemoTenant.ID, "LAPTOP-001"));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(product(DemoTenant.ID, "LAPTOP-001")));
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(
                new Product(DemoTenant.ID, "ZERO", "Zero weight", BigDecimal.ZERO)));
    }

    private Product product(UUID tenant, String sku) {
        return new Product(tenant, sku, "Laptop", new BigDecimal("1.250"));
    }

    private String body(String sku, String name, String weight) {
        return "{\"sku\":\"" + sku + "\",\"name\":\"" + name + "\",\"weightKg\":" + weight + "}";
    }
}
