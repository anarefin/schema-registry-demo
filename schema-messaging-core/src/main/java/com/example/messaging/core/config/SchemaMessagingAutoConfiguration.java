package com.example.messaging.core.config;

import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.consumer.IdempotencyFilter;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.health.RegistryHealthIndicator;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.observability.SchemaMessagingMetrics;
import com.example.messaging.core.publisher.EventPublisher;
import com.example.messaging.core.registry.ApicurioClient;
import com.example.messaging.core.registry.ApicurioCacheProperties;
import com.example.messaging.core.registry.CachePreWarmer;
import com.example.messaging.core.registry.SchemaResolver;
import com.example.messaging.core.serde.JsonSchemaStrategy;
import com.example.messaging.core.serde.ProtobufStrategy;
import com.example.messaging.core.serde.SerializationStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.apicurio.registry.client.common.RegistryClientOptions;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import com.example.messaging.core.registry.StartupSchemaValidator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Spring Boot auto-configuration for schema-messaging-core (T-1.13).
 * Wires all core beans; services can override any bean with their own {@code @Bean}.
 */
@AutoConfiguration
@EnableConfigurationProperties(ApicurioCacheProperties.class)
public class SchemaMessagingAutoConfiguration {

    /**
     * T-6.7 — OIDC enablement (spec §16):
     *
     * <p>Override this bean in your service's {@code @Configuration} to enable OIDC:
     * <pre>{@code
     * @Bean
     * ApicurioClient apicurioClient(
     *         @Value("${apicurio.registry.url}") String url,
     *         @Value("${apicurio.auth.token-url}") String tokenUrl,
     *         @Value("${apicurio.auth.client-id}") String clientId,
     *         @Value("${apicurio.auth.client-secret}") String clientSecret) {
     *     RegistryClientOptions opts = RegistryClientOptions.create(url);
     *     opts.oauth2(tokenUrl, clientId, clientSecret);
     *     return new ApicurioClient(opts);
     * }
     * }</pre>
     *
     * <p>For Keycloak: {@code token-url} = {@code http://<keycloak>:8080/realms/<realm>/protocol/openid-connect/token}.
     * Leave disabled by default for POC (anonymous access).
     */
    @Bean
    @ConditionalOnMissingBean
    public ApicurioClient apicurioClient(
            @Value("${apicurio.registry.url:http://localhost:8080}") String registryUrl) {
        return new ApicurioClient(RegistryClientOptions.create(registryUrl));
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemaResolver schemaResolver(ApicurioClient apicurioClient, ApicurioCacheProperties props,
                                         SchemaMessagingMetrics metrics) {
        return new SchemaResolver(apicurioClient, props, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public TypeMappingRegistry typeMappingRegistry(List<TypeMapping> mappings) {
        return new TypeMappingRegistry(mappings);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProtobufStrategy protobufStrategy() {
        return new ProtobufStrategy();
    }

    /**
     * Fallback Jackson 2 ObjectMapper. Spring Boot 4.0's JacksonAutoConfiguration only
     * auto-configures Jackson 3 (tools.jackson); this project uses Jackson 2 (com.fasterxml).
     * Services that import spring-boot-starter-web (which pulls spring-boot-starter-jackson
     * for Jackson 3) will not have a Jackson 2 ObjectMapper unless this bean is provided.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonSchemaStrategy jsonSchemaStrategy(ObjectMapper objectMapper) {
        return new JsonSchemaStrategy(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public SchemaAwareMessageConverter schemaAwareMessageConverter(
            TypeMappingRegistry typeMappingRegistry,
            SchemaResolver schemaResolver,
            List<SerializationStrategy> strategies,
            SchemaMessagingMetrics metrics) {
        return new SchemaAwareMessageConverter(typeMappingRegistry, schemaResolver, strategies, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher(
            RabbitTemplate rabbitTemplate,
            MessageConverter messageConverter,
            TypeMappingRegistry typeMappingRegistry) {
        return new EventPublisher(rabbitTemplate, messageConverter, typeMappingRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventConsumerSupport eventConsumerSupport() {
        return new EventConsumerSupport();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyFilter idempotencyFilter() {
        return new IdempotencyFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public CachePreWarmer cachePreWarmer(SchemaResolver schemaResolver, TypeMappingRegistry typeMappingRegistry) {
        return new CachePreWarmer(schemaResolver, typeMappingRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemaMessagingMetrics schemaMessagingMetrics() {
        return new SchemaMessagingMetrics();
    }

    // T-6.5: Registry health indicator — only registered when spring-boot-actuator is on classpath.
    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(RegistryHealthIndicator.class)
    public RegistryHealthIndicator registryHealthIndicator(ApicurioClient apicurioClient,
                                                           CachePreWarmer cachePreWarmer) {
        return new RegistryHealthIndicator(apicurioClient, cachePreWarmer);
    }

    // T-6.6: Fail-fast startup validator — active only when apicurio.auto-register=OFF.
    @Bean
    @ConditionalOnProperty(name = "apicurio.auto-register", havingValue = "OFF")
    @ConditionalOnMissingBean
    public StartupSchemaValidator startupSchemaValidator(ApicurioClient apicurioClient,
                                                         TypeMappingRegistry typeMappingRegistry) {
        return new StartupSchemaValidator(apicurioClient, typeMappingRegistry);
    }

}
