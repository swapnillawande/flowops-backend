package com.flowops.warehouse.controller;

import com.flowops.support.PostgresIntegrationTest;
import com.flowops.support.AuthenticatedApiTestConfiguration;
import org.springframework.context.annotation.Import;
import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthenticatedApiTestConfiguration.class)
class WarehouseApiTest extends PostgresIntegrationTest {

    private static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String WAREHOUSES = "/api/v1/warehouses";

    @Autowired MockMvc mvc;
    @Autowired WarehouseRepository repository;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAllInBatch();
    }

    @Test
    void healthEndpointsRespond() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("application").value("FlowOps AI"))
                .andExpect(jsonPath("status").value("UP"));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("status").value("UP"));
    }

    @Test
    void createsNormalizesAndRetrievesWarehouse() throws Exception {
        mvc.perform(post(WAREHOUSES).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  Berlin Central  ","city":"  Berlin  ","countryCode":"de"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("name").value("Berlin Central"))
                .andExpect(jsonPath("city").value("Berlin"))
                .andExpect(jsonPath("countryCode").value("DE"))
                .andExpect(jsonPath("createdAt").exists())
                .andExpect(result -> mvc.perform(get(result.getResponse().getHeader("Location")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("name").value("Berlin Central")));
        mvc.perform(get(WAREHOUSES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void duplicateNamesAreCaseInsensitiveAndTrimmed() throws Exception {
        repository.saveAndFlush(new Warehouse(DEMO_TENANT, "Berlin Central", "Berlin", "DE"));
        mvc.perform(post(WAREHOUSES).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" berlin central ","city":"Berlin","countryCode":"DE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("error").isString());
    }

    @Test
    void databaseEnforcesUniquenessEvenWhenPrecheckIsBypassed() {
        repository.saveAndFlush(new Warehouse(DEMO_TENANT, "Berlin", "Berlin", "DE"));
        assertThrows(DataIntegrityViolationException.class, () ->
                repository.saveAndFlush(new Warehouse(DEMO_TENANT, "BERLIN", "Berlin", "DE")));
        repository.saveAndFlush(new Warehouse(UUID.randomUUID(), "Berlin", "Berlin", "DE"));
    }

    @Test
    void anotherTenantsWarehouseIsNeitherListedNorRetrievable() throws Exception {
        Warehouse other = repository.saveAndFlush(
                new Warehouse(UUID.randomUUID(), "Private warehouse", "Berlin", "DE"));
        mvc.perform(get(WAREHOUSES))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get(WAREHOUSES + "/" + other.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("error").isString());
    }

    @Test
    void unknownWarehouseReturnsNotFound() throws Exception {
        mvc.perform(get(WAREHOUSES + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidFields() throws Exception {
        for (String body : new String[]{
                "{\"name\":\" \",\"city\":\"Berlin\",\"countryCode\":\"DE\"}",
                "{\"name\":\"Berlin\",\"city\":\"\",\"countryCode\":\"DE\"}",
                "{\"name\":\"Berlin\",\"city\":\"Berlin\",\"countryCode\":\"DEU\"}",
                "{}"}) {
            mvc.perform(post(WAREHOUSES).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("error").isString());
        }
    }

    @Test
    void malformedJsonAndIdentifiersHaveConsistentErrors() throws Exception {
        mvc.perform(post(WAREHOUSES).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("error").value("Malformed request body or invalid parameter"));
        mvc.perform(get(WAREHOUSES + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("error").value("Malformed request body or invalid parameter"));
    }
}
