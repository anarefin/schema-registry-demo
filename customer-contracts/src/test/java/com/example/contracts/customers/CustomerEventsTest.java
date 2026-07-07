package com.example.contracts.customers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Value-object tests for the code-first customer events. The record IS the contract; the wire
 * format is the raw JSON document only (spec §6 — no envelope). These verify Jackson round-trips
 * the record shape faithfully, including the {@link CustomerTier} enum and nested {@link Address}
 * object that make these events the "enrichment" showcases in the generated schemas.
 */
class CustomerEventsTest {

    // findAndAddModules() picks up jackson-datatype-jsr310 (test scope) so Instant renders as
    // ISO-8601 — the same shape the runtime converter emits.
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void customerRegisteredRoundTripsWithOptionalPhone() throws Exception {
        CustomerRegistered withPhone = new CustomerRegistered(
                UUID.randomUUID(), "ada@example.com", "Ada", "Lovelace", "+14155552671",
                Instant.parse("2026-05-31T00:00:00Z"));
        CustomerRegistered withoutPhone = new CustomerRegistered(
                UUID.randomUUID(), "grace@example.com", "Grace", "Hopper", null,
                Instant.parse("2026-05-31T00:00:00Z"));

        assertThat(mapper.readValue(mapper.writeValueAsBytes(withPhone), CustomerRegistered.class))
                .isEqualTo(withPhone);

        CustomerRegistered parsed =
                mapper.readValue(mapper.writeValueAsBytes(withoutPhone), CustomerRegistered.class);
        assertThat(parsed).isEqualTo(withoutPhone);
        assertThat(parsed.phoneNumber()).isNull();
    }

    @Test
    void customerAddressAddedRoundTripsNestedObject() throws Exception {
        Address address = new Address("221B Baker Street", null, "London", "NW1 6XE", "GB");
        CustomerAddressAdded original = new CustomerAddressAdded(
                UUID.randomUUID(), address, Instant.parse("2026-06-02T08:15:00Z"));

        var json = mapper.readTree(mapper.writeValueAsBytes(original));
        assertThat(json.get("address").isObject()).isTrue();
        assertThat(json.get("address").get("city").asText()).isEqualTo("London");
        assertThat(json.get("address").get("countryCode").asText()).isEqualTo("GB");

        CustomerAddressAdded parsed =
                mapper.readValue(mapper.writeValueAsBytes(original), CustomerAddressAdded.class);
        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.address().line2()).isNull();
    }

    @Test
    void customerTierChangedRendersEnumAsName() throws Exception {
        CustomerTierChanged original = new CustomerTierChanged(
                UUID.randomUUID(), CustomerTier.BRONZE, CustomerTier.GOLD,
                Instant.parse("2026-06-03T09:00:00Z"));

        var json = mapper.readTree(mapper.writeValueAsBytes(original));
        assertThat(json.get("newTier").asText()).isEqualTo("GOLD");
        assertThat(json.get("previousTier").asText()).isEqualTo("BRONZE");

        CustomerTierChanged parsed =
                mapper.readValue(mapper.writeValueAsBytes(original), CustomerTierChanged.class);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void customerTierChangedRoundTripsWithoutPreviousTier() throws Exception {
        CustomerTierChanged original = new CustomerTierChanged(
                UUID.randomUUID(), null, CustomerTier.SILVER, Instant.parse("2026-06-03T09:00:00Z"));

        CustomerTierChanged parsed =
                mapper.readValue(mapper.writeValueAsBytes(original), CustomerTierChanged.class);

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.previousTier()).isNull();
    }
}
