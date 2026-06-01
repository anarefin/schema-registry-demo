package com.example.messaging.core.registry;

import com.example.messaging.core.exception.SchemaNotFoundException;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.model.SchemaCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;

/**
 * T-6.6 (TC-6.5): Fail-fast startup validator active when {@code apicurio.auto-register=OFF}.
 *
 * <p>On startup (bean initialization), attempts to resolve every registered {@link TypeMapping}
 * coordinate via the {@link ApicurioClient}. If any pinned (or latest) schema is not found in
 * the registry, throws {@link IllegalStateException} to abort context startup with a clear error.
 *
 * <p>This prevents services from starting in a state where they cannot serialize or deserialize
 * any message because the schema is missing. When {@code apicurio.auto-register=ON} (default),
 * this bean is not registered and the pre-warmer's soft-fail behaviour applies instead.
 */
public class StartupSchemaValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StartupSchemaValidator.class);

    private final ApicurioClient apicurioClient;
    private final TypeMappingRegistry typeMappingRegistry;

    public StartupSchemaValidator(ApicurioClient apicurioClient, TypeMappingRegistry typeMappingRegistry) {
        this.apicurioClient = apicurioClient;
        this.typeMappingRegistry = typeMappingRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        List<SchemaCoordinates> missing = new ArrayList<>();
        for (TypeMapping mapping : typeMappingRegistry.all()) {
            SchemaCoordinates coords = mapping.coordinates();
            try {
                apicurioClient.fetchByCoordinates(coords);
                log.info("[startup-validator] Schema verified: {}", coords);
            } catch (SchemaNotFoundException e) {
                log.error("[startup-validator] Schema NOT FOUND: {} — refusing to start (auto-register=OFF)", coords);
                missing.add(coords);
            } catch (Exception e) {
                log.error("[startup-validator] Registry unreachable while validating {} — refusing to start", coords, e);
                missing.add(coords);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Startup aborted: " + missing.size() + " schema(s) not found in registry " +
                    "(apicurio.auto-register=OFF). Missing: " + missing);
        }
        log.info("[startup-validator] All {} schema(s) verified in registry.", typeMappingRegistry.all().size());
    }
}
