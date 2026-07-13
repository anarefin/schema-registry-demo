package com.example.messaging.core.consumer;

import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

import java.util.Set;

/**
 * Computes the set of {@link TypeMapping}s this service actually handles (via
 * {@link BitsEventHandlerScanner#discoverHandledTypeMappings}) exactly once and shares the
 * result with every consumer that needs it — {@code ServiceQueueTopologyAutoConfiguration}
 * (queue/DLQ/retry declaration) and {@code QueueDepthHealthIndicator} (per-probe depth
 * reporting) — instead of each independently re-running the {@code getBeanDefinitionNames()} +
 * reflection scan. {@link BitsEventHandlerRegistrar} still runs its own single pass (driven by
 * {@code RabbitListenerConfigurer}'s own startup hook, which needs bean/method pairs rather than
 * just {@link TypeMapping}s) but shares the same underlying {@link BitsEventHandlerScanner}
 * algorithm, so all three consumers agree on the handled set.
 *
 * <p>Warms during {@link #afterSingletonsInstantiated()} — the same lifecycle hook
 * {@code ServiceQueueTopologyConfigurer} already uses to declare topology. Spring does not
 * guarantee relative ordering between multiple {@link SmartInitializingSingleton} beans, so
 * {@link #handledTypeMappings()} is also safe to call before that callback fires: it lazily
 * computes and memoizes the result on first access either way.
 */
public class HandledEventTypesCache implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;
    private final TypeMappingRegistry typeMappingRegistry;
    private Set<TypeMapping> handledTypeMappings;

    public HandledEventTypesCache(ApplicationContext applicationContext, TypeMappingRegistry typeMappingRegistry) {
        this.applicationContext = applicationContext;
        this.typeMappingRegistry = typeMappingRegistry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        handledTypeMappings();
    }

    public synchronized Set<TypeMapping> handledTypeMappings() {
        if (handledTypeMappings == null) {
            handledTypeMappings = Set.copyOf(
                    BitsEventHandlerScanner.discoverHandledTypeMappings(applicationContext, typeMappingRegistry));
        }
        return handledTypeMappings;
    }
}
