package com.example.messaging.core.config;

import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.publisher.EventPublisher;
import com.example.messaging.core.schema.LocalSchemaCatalog;
import com.example.messaging.core.serde.JsonSchemaStrategy;
import com.example.messaging.core.serde.SerializationStrategy;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Spring Boot auto-configuration for schema-messaging-core (T-1.13).
 * Wires all core beans; services can override any bean with their own {@code @Bean}.
 */
@AutoConfiguration
public class SchemaMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TypeMappingRegistry typeMappingRegistry(List<TypeMapping> mappings) {
        return new TypeMappingRegistry(mappings);
    }

    @Bean
    @ConditionalOnMissingBean
    public LocalSchemaCatalog localSchemaCatalog(TypeMappingRegistry typeMappingRegistry) {
        return new LocalSchemaCatalog(typeMappingRegistry);
    }

    /**
     * Fallback Jackson 2 ObjectMapper. Spring Boot 4.0's JacksonAutoConfiguration only
     * auto-configures Jackson 3 (tools.jackson); this project uses Jackson 2 (com.fasterxml).
     * Services that import spring-boot-starter-web (which pulls spring-boot-starter-jackson
     * for Jackson 3) will not have a Jackson 2 ObjectMapper unless this bean is provided.
     *
     * <p>{@code findAndRegisterModules()} picks up jackson-datatype-jsr310 (declared as a direct
     * dependency of schema-messaging-core; Spring Boot 4.0's jackson starter ships Jackson 3, not
     * this Jackson 2 module) so the code-first records' {@code java.time.Instant} fields serialize as
     * ISO-8601 strings — matching the generated schema's {@code "type":"string","format":"date-time"}
     * — rather than numeric timestamps. Null optionals (e.g. {@code refundAmount}, {@code phoneNumber})
     * are omitted so they don't fail the {@code type} check for an absent field.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
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
            LocalSchemaCatalog localSchemaCatalog,
            List<SerializationStrategy> strategies) {
        return new SchemaAwareMessageConverter(typeMappingRegistry, localSchemaCatalog, strategies);
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
