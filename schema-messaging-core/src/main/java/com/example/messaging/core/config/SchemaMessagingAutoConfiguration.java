package com.example.messaging.core.config;

import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.publisher.EventPublisher;
import com.example.messaging.core.registry.ApicurioCacheProperties;
import com.example.messaging.core.registry.ApicurioClient;
import com.example.messaging.core.registry.SchemaResolver;
import com.example.messaging.core.serde.JsonSchemaStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.apicurio.registry.client.common.RegistryClientOptions;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    /** Anonymous-access Apicurio client. (The original POC's OIDC path was dropped in the minimal cut.) */
    @Bean
    @ConditionalOnMissingBean
    public ApicurioClient apicurioClient(
            @Value("${apicurio.registry.url:http://localhost:8080}") String registryUrl) {
        return new ApicurioClient(RegistryClientOptions.create(registryUrl));
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemaResolver schemaResolver(ApicurioClient apicurioClient, ApicurioCacheProperties cacheProperties) {
        return new SchemaResolver(apicurioClient, cacheProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TypeMappingRegistry typeMappingRegistry(List<TypeMapping> mappings) {
        return new TypeMappingRegistry(mappings);
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
        return new JsonSchemaStrategy(objectMapper, true);
    }

    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public SchemaAwareMessageConverter schemaAwareMessageConverter(
            TypeMappingRegistry typeMappingRegistry,
            SchemaResolver schemaResolver,
            JsonSchemaStrategy jsonSchemaStrategy) {
        return new SchemaAwareMessageConverter(typeMappingRegistry, schemaResolver, jsonSchemaStrategy);
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

}
