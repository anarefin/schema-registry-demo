package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaType;
import com.google.protobuf.StringValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-serde-1 round-trip · TC-serde-2 non-Message payload · TC-serde-3 parseFrom Method cache
 */
class ProtobufStrategyTest {

    private ProtobufStrategy strategy;

    private static final ResolvedSchema SCHEMA =
            new ResolvedSchema(1L, SchemaType.PROTOBUF, new byte[0]);

    @BeforeEach
    void setUp() {
        strategy = new ProtobufStrategy();
    }

    /** TC-serde-1: serialize then deserialize produces an equal Protobuf message. */
    @Test
    void roundTrip_serializeThenDeserialize() throws Exception {
        StringValue original = StringValue.of("hello-proto");

        byte[] bytes = strategy.serialize(original, SCHEMA);
        Object result = strategy.deserialize(bytes, StringValue.class, SCHEMA);

        assertThat(result).isEqualTo(original);
    }

    /** TC-serde-2: non-Message payload → SerializationException. */
    @Test
    void serialize_nonMessagePayload_throwsSerializationException() {
        assertThatThrownBy(() -> strategy.serialize("not a proto", SCHEMA))
                .isInstanceOf(SerializationException.class);
    }

    /** TC-serde-3: parseFrom Method is cached after first call — second call doesn't re-lookup. */
    @Test
    @SuppressWarnings("unchecked")
    void deserialize_parseFromMethodIsCached() throws Exception {
        StringValue msg = StringValue.of("cache-test");
        byte[] bytes = strategy.serialize(msg, SCHEMA);

        // First call populates the cache
        strategy.deserialize(bytes, StringValue.class, SCHEMA);

        // Introspect internal cache via reflection
        Field cacheField = ProtobufStrategy.class.getDeclaredField("parseFromCache");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(strategy);

        assertThat(cache.containsKey(StringValue.class)).isTrue();

        // Second call uses cache (no exception from repeated lookup)
        Object second = strategy.deserialize(bytes, StringValue.class, SCHEMA);
        assertThat(second).isEqualTo(msg);
    }

    /** TC-serde-4: truncated/unparseable bytes produce DeserializationException. */
    @Test
    void deserialize_truncatedBytes_throwsDeserializationException() {
        // 0x0A = field 1 (string), wire type 2 (length-delimited); 0x64 = length 100.
        // No payload bytes follow — protobuf throws InvalidProtocolBufferException (truncated).
        assertThatThrownBy(() -> strategy.deserialize(new byte[]{0x0A, 0x64}, StringValue.class, SCHEMA))
                .isInstanceOf(DeserializationException.class);
    }
}
