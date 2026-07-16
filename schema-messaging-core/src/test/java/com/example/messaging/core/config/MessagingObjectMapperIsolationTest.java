package com.example.messaging.core.config;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Defends messaging deserialization against a strict application-level {@link ObjectMapper}.
 * A service can legitimately register its own {@code @Bean ObjectMapper} (e.g. with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} for its REST layer); that bean must never displace the
 * tolerant mapper {@link com.example.messaging.core.serde.JsonSchemaStrategy} builds for itself.
 */
class MessagingObjectMapperIsolationTest {

    private static final TypeMapping FIXTURE_MAPPING = new TypeMapping(
            FixtureEvent.class,
            new SchemaCoordinates("events.test", "FixtureEvent"),
            SchemaType.JSON,
            "test.fixture",
            "events.test.exchange");

    record FixtureEvent(String id) {}

    private static ObjectMapper strictAppObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        return mapper;
    }

    private ApplicationContextRunner runnerWithStrictAppObjectMapper() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaMessagingAutoConfiguration.class))
                .withBean(TypeMapping.class, () -> FIXTURE_MAPPING)
                .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
                .withBean("appObjectMapper", ObjectMapper.class,
                        MessagingObjectMapperIsolationTest::strictAppObjectMapper);
    }

    @Test
    void appObjectMapper_isTheOnlyObjectMapperBean_andIsStrict() {
        runnerWithStrictAppObjectMapper().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectMapper.class);
            ObjectMapper appMapper = context.getBean(ObjectMapper.class);

            // Sanity check: this bean really is strict, so the isolation assertion below can't
            // pass by accident (e.g. because the bean never got wired at all).
            assertThatThrownBy(() -> appMapper.readValue(
                    "{\"id\":\"evt-1\",\"extra\":\"nope\"}", FixtureEvent.class))
                    .isInstanceOf(UnrecognizedPropertyException.class);
        });
    }

    @Test
    void fromMessage_extraUnknownField_toleratedDespiteStrictAppObjectMapperInContext() {
        runnerWithStrictAppObjectMapper().run(context -> {
            assertThat(context).hasNotFailed();
            SchemaAwareMessageConverter converter = context.getBean(SchemaAwareMessageConverter.class);

            byte[] bodyWithExtraField =
                    "{\"id\":\"evt-1\",\"extra\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
            MessageProperties props = new MessageProperties();
            props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.test");
            props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "FixtureEvent");
            props.setHeader(SchemaMessageHeaders.TYPE, "JSON");
            props.setContentType("application/json");

            Object result = converter.fromMessage(new Message(bodyWithExtraField, props));

            assertThat(result).isEqualTo(new FixtureEvent("evt-1"));
        });
    }
}
