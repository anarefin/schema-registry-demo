package com.example.schemagen;

import com.example.schemagen.mappingfixtures.AbstractShape;
import com.example.schemagen.mappingfixtures.BlankExchange;
import com.example.schemagen.mappingfixtures.BlankGroupId;
import com.example.schemagen.mappingfixtures.BlankRoutingKey;
import com.example.schemagen.mappingfixtures.CoordClashOne;
import com.example.schemagen.mappingfixtures.CoordClashTwo;
import com.example.schemagen.mappingfixtures.EventEnum;
import com.example.schemagen.mappingfixtures.EventInterface;
import com.example.schemagen.mappingfixtures.GoodOrderCreated;
import com.example.schemagen.mappingfixtures.GoodOrderShipped;
import com.example.schemagen.mappingfixtures.NotARecord;
import com.example.schemagen.mappingfixtures.Outer;
import com.example.schemagen.mappingfixtures.URLPing;
import com.example.schemagen.mappingfixtures.UnpairedRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventMappingValidatorTest {

    @Test
    void producesEffectiveMetadataSortedByFqcn() {
        List<EventMappingMetadata> metadata = EventMappingValidator.validate(
                List.of(GoodOrderShipped.class, GoodOrderCreated.class));

        assertThat(metadata).extracting(EventMappingMetadata::fqcn).containsExactly(
                GoodOrderCreated.class.getName(),
                GoodOrderShipped.class.getName());

        EventMappingMetadata created = metadata.get(0);
        assertThat(created.groupId()).isEqualTo("events.orders");
        assertThat(created.artifactId()).isEqualTo("GoodOrderCreated"); // defaulted to simple name
        assertThat(created.beanName()).isEqualTo("goodOrderCreatedMapping");

        EventMappingMetadata shipped = metadata.get(1);
        assertThat(shipped.artifactId()).isEqualTo("OrderShipped"); // explicit
        assertThat(shipped.beanName()).isEqualTo("goodOrderShippedMapping");
    }

    @Test
    void leadingAcronymBeanNameMatchesRuntimeAlgorithm() {
        List<EventMappingMetadata> metadata = EventMappingValidator.validate(List.of(URLPing.class));

        assertThat(metadata).singleElement()
                .extracting(EventMappingMetadata::beanName)
                .isEqualTo("URLPingMapping");
    }

    @Test
    void rejectsUnpairedGenerateSchemaType() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(UnpairedRecord.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a paired @EventMapping")
                .hasMessageContaining(UnpairedRecord.class.getName());
    }

    @Test
    void rejectsNonRecordType() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(NotARecord.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a record");
    }

    @Test
    void rejectsAbstractType() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(AbstractShape.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a record");
    }

    @Test
    void rejectsInterfaceType() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(EventInterface.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a record, not an interface");
    }

    @Test
    void rejectsEnumType() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(EventEnum.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a record, not an enum");
    }

    @Test
    void rejectsNonPublicType() {
        // Reference the package-private record reflectively so the test class stays clean.
        Class<?> packagePrivate = firstNested("com.example.schemagen.mappingfixtures.PackagePrivateRecord");
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(packagePrivate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be public");
    }

    @Test
    void rejectsNestedMemberType() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(Outer.Nested.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-level");
    }

    @Test
    void rejectsLocalType() {
        record LocalEvent(String id) {}
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(LocalEvent.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-level");
    }

    @Test
    void rejectsBlankGroupId() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(BlankGroupId.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("groupId")
                .hasMessageContaining("must be non-blank");
    }

    @Test
    void rejectsBlankExchange() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(BlankExchange.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exchange")
                .hasMessageContaining("must be non-blank");
    }

    @Test
    void rejectsBlankRoutingKey() {
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(BlankRoutingKey.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("routingKey")
                .hasMessageContaining("must be non-blank");
    }

    @Test
    void rejectsDuplicateJavaType() {
        assertThatThrownBy(() ->
                EventMappingValidator.validate(List.of(GoodOrderCreated.class, GoodOrderCreated.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate @GenerateSchema type");
    }

    @Test
    void rejectsDuplicateEffectiveCoordinates() {
        assertThatThrownBy(() ->
                EventMappingValidator.validate(List.of(CoordClashOne.class, CoordClashTwo.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate effective schema coordinates")
                .hasMessageContaining("events.clash")
                .hasMessageContaining("Same");
    }

    @Test
    void rejectsDeterministicBeanNameCollision() {
        Class<?> one = firstNested("com.example.schemagen.mappingfixtures.beanclash.Widget");
        Class<?> two = firstNested("com.example.schemagen.mappingfixtures.beanclash.sub.Widget");
        assertThatThrownBy(() -> EventMappingValidator.validate(List.of(one, two)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bean name")
                .hasMessageContaining("widgetMapping");
    }

    private static Class<?> firstNested(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
