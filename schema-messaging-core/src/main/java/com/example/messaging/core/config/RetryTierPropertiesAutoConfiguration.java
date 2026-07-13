package com.example.messaging.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Binds the {@code events.retry.tier{0,1,2}.ms} properties exactly once (spec §9/§11). Both
 * {@link ServiceQueueTopologyAutoConfiguration} and {@link SchemaMessagingConsumerAutoConfiguration}
 * consume the resulting {@link RetryTierProperties} bean instead of re-declaring the same
 * {@code @Value} bindings themselves — changing or adding a tier here applies everywhere.
 */
@AutoConfiguration
public class RetryTierPropertiesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RetryTierProperties retryTierProperties(
            @Value("${events.retry.tier0.ms:5000}") long tier0Ms,
            @Value("${events.retry.tier1.ms:30000}") long tier1Ms,
            @Value("${events.retry.tier2.ms:300000}") long tier2Ms) {
        return new RetryTierProperties(tier0Ms, tier1Ms, tier2Ms);
    }
}
