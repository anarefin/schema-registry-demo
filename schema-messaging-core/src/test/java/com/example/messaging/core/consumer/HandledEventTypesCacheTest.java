package com.example.messaging.core.consumer;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandledEventTypesCacheTest {

    record DemoEvent(String id) {}

    static class Handler {
        @BitsEventHandler
        public void onDemo(DemoEvent event) {}
    }

    private static TypeMapping mapping() {
        return new TypeMapping(
                DemoEvent.class,
                new SchemaCoordinates("events.demo", "DemoEvent"),
                SchemaType.JSON,
                "demo.event",
                "events.demo.exchange");
    }

    private static ApplicationContext contextWithHandler(TypeMapping mapping) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeanDefinitionNames()).thenReturn(new String[] {"handler"});
        when(ctx.getBean("handler")).thenReturn(new Handler());
        return ctx;
    }

    @Test
    void handledTypeMappings_scansOnlyOnceAcrossRepeatedCalls() {
        TypeMapping mapping = mapping();
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));
        ApplicationContext ctx = contextWithHandler(mapping);
        HandledEventTypesCache cache = new HandledEventTypesCache(ctx, registry);

        Set<TypeMapping> first = cache.handledTypeMappings();
        Set<TypeMapping> second = cache.handledTypeMappings();
        Set<TypeMapping> third = cache.handledTypeMappings();

        assertThat(first).containsExactly(mapping);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        verify(ctx, times(1)).getBeanDefinitionNames();
    }

    @Test
    void afterSingletonsInstantiated_warmsCacheSoLaterAccessorsDoNotRescan() {
        TypeMapping mapping = mapping();
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));
        ApplicationContext ctx = contextWithHandler(mapping);
        HandledEventTypesCache cache = new HandledEventTypesCache(ctx, registry);

        cache.afterSingletonsInstantiated();
        verify(ctx, times(1)).getBeanDefinitionNames();

        Set<TypeMapping> handled = cache.handledTypeMappings();

        assertThat(handled).containsExactly(mapping);
        verify(ctx, times(1)).getBeanDefinitionNames();
    }
}
