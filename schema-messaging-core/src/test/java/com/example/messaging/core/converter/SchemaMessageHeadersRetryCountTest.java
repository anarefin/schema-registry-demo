package com.example.messaging.core.converter;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMessageHeadersRetryCountTest {

    @Test
    void nullHeader_isZero() {
        assertThat(SchemaMessageHeaders.getRetryCount(new MessageProperties())).isZero();
    }

    @Test
    void numberHeader_isParsed() {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.RETRY_COUNT, 2);
        assertThat(SchemaMessageHeaders.getRetryCount(props)).isEqualTo(2);
    }

    @Test
    void negativeNumber_isInvalidSentinel() {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.RETRY_COUNT, -1);
        assertThat(SchemaMessageHeaders.getRetryCount(props)).isEqualTo(-1);
    }

    @Test
    void nonNumericString_isInvalidSentinel() {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.RETRY_COUNT, "abc");
        assertThat(SchemaMessageHeaders.getRetryCount(props)).isEqualTo(-1);
    }
}
