package com.example.messaging.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RetryTierPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RetryTierPropertiesAutoConfiguration.class));

    @Test
    void defaults_matchPreviouslyHardcodedTierTtls() {
        contextRunner.run(context -> {
            RetryTierProperties properties = context.getBean(RetryTierProperties.class);
            assertThat(properties.toArray()).containsExactly(5_000L, 30_000L, 300_000L);
        });
    }

    @Test
    void customValues_bindFromEventsRetryDottedPropertyKeys() {
        contextRunner
                .withPropertyValues(
                        "events.retry.tier0.ms=111",
                        "events.retry.tier1.ms=222",
                        "events.retry.tier2.ms=333")
                .run(context -> {
                    RetryTierProperties properties = context.getBean(RetryTierProperties.class);
                    assertThat(properties.toArray()).containsExactly(111L, 222L, 333L);
                });
    }
}
