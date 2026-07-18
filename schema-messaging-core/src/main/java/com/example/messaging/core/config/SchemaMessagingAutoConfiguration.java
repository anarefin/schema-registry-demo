package com.example.messaging.core.config;

import com.example.messaging.core.consumer.BitsEventHandlerScanner;
import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.mapping.TypeMappingSelection;
import com.example.messaging.core.mapping.TypeMappingSelectionProperties;
import com.example.messaging.core.publisher.EventPublisher;
import com.example.messaging.core.schema.LocalSchemaCatalog;
import com.example.messaging.core.serde.JsonSchemaStrategy;
import com.example.messaging.core.serde.SerializationStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Set;

/**
 * Spring Boot auto-configuration for schema-messaging-core (T-1.13).
 * Wires all core beans; services can override any bean with their own {@code @Bean}.
 *
 * <p>{@link TypeMappingRegistry} / {@link LocalSchemaCatalog} are scoped: when
 * {@code @BitsEventHandler} methods exist, only handled event types are registered and warmed
 * (see {@link TypeMappingSelection}). Optional {@code events.mappings.include}/{@code exclude}
 * override or trim further.
 */
@AutoConfiguration
@EnableConfigurationProperties(TypeMappingSelectionProperties.class)
public class SchemaMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TypeMappingRegistry typeMappingRegistry(
            List<TypeMapping> mappings,
            ApplicationContext applicationContext,
            TypeMappingSelectionProperties selectionProperties) {
        Set<Class<?>> handledTypes = BitsEventHandlerScanner.discoverHandledJavaTypes(applicationContext);
        List<TypeMapping> selected = TypeMappingSelection.select(mappings, handledTypes, selectionProperties);
        return new TypeMappingRegistry(selected);
    }

    @Bean
    @ConditionalOnMissingBean
    public LocalSchemaCatalog localSchemaCatalog(TypeMappingRegistry typeMappingRegistry) {
        return new LocalSchemaCatalog(typeMappingRegistry);
    }

    /**
     * Fallback Jackson 2 ObjectMapper for non-messaging needs only. Spring Boot 4.0's
     * JacksonAutoConfiguration only auto-configures Jackson 3 (tools.jackson); this project uses
     * Jackson 2 (com.fasterxml). Services that import spring-boot-starter-web (which pulls
     * spring-boot-starter-jackson for Jackson 3) will not have a Jackson 2 ObjectMapper unless
     * this bean is provided.
     *
     * <p><b>Not used by messaging:</b> {@link JsonSchemaStrategy} builds and owns its own
     * tolerant mapper internally rather than accepting one from the application context, so that
     * a service's own strict {@code @Bean ObjectMapper} (e.g. {@code FAIL_ON_UNKNOWN_PROPERTIES=
     * true} for its REST layer) can satisfy this {@code @ConditionalOnMissingBean} without ever
     * affecting event deserialization. This bean starts from {@link JsonSchemaStrategy}'s same
     * baseline config (avoiding a hand-copied duplicate) but gets its own independently
     * constructed {@link ObjectMapper} instance — never the same object messaging uses — and is
     * free to layer further, non-messaging-specific config on top without touching messaging.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return JsonSchemaStrategy.defaultObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonSchemaStrategy jsonSchemaStrategy() {
        return new JsonSchemaStrategy();
    }

    @Bean
    @ConditionalOnMissingBean(SchemaAwareMessageConverter.class)
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
            SchemaAwareMessageConverter messageConverter,
            TypeMappingRegistry typeMappingRegistry) {
        return new EventPublisher(rabbitTemplate, messageConverter, typeMappingRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventConsumerSupport eventConsumerSupport() {
        return new EventConsumerSupport();
    }

}
