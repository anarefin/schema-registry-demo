package com.example.consumer.health;

import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.health.contributor.Health;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueDepthHealthIndicatorTest {

    private static final String SERVICE = "consumer-service";

    record DemoEvent(String id) {}

    private static TypeMapping mapping() {
        return new TypeMapping(
                DemoEvent.class,
                new SchemaCoordinates("events.demo", "DemoEvent"),
                SchemaType.JSON,
                "demo.event",
                "events.demo.exchange");
    }

    @Test
    void health_readsHandledMappingsFromCache_notFromABeanScan() {
        TypeMapping mapping = mapping();
        HandledEventTypesCache cache = mock(HandledEventTypesCache.class);
        when(cache.handledTypeMappings()).thenReturn(Set.of(mapping));

        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        QueueInformation emptyQueue = mock(QueueInformation.class);
        when(emptyQueue.getMessageCount()).thenReturn(0L);
        when(rabbitAdmin.getQueueInfo(TopologyNaming.serviceQueueName("demo.event", SERVICE)))
                .thenReturn(emptyQueue);
        when(rabbitAdmin.getQueueInfo(TopologyNaming.serviceDlqName("demo.event", SERVICE)))
                .thenReturn(emptyQueue);

        QueueDepthHealthIndicator indicator = new QueueDepthHealthIndicator(rabbitAdmin, cache, SERVICE);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        verify(cache, times(1)).handledTypeMappings();
    }

    @Test
    void health_repeatedProbes_reuseTheSameCachedSet_withNoRescanTriggeredByTheIndicator() {
        TypeMapping mapping = mapping();
        HandledEventTypesCache cache = mock(HandledEventTypesCache.class);
        when(cache.handledTypeMappings()).thenReturn(Set.of(mapping));

        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        QueueInformation withMessages = mock(QueueInformation.class);
        when(withMessages.getMessageCount()).thenReturn(2L);
        when(rabbitAdmin.getQueueInfo(TopologyNaming.serviceQueueName("demo.event", SERVICE)))
                .thenReturn(withMessages);
        when(rabbitAdmin.getQueueInfo(TopologyNaming.serviceDlqName("demo.event", SERVICE)))
                .thenReturn(withMessages);

        QueueDepthHealthIndicator indicator = new QueueDepthHealthIndicator(rabbitAdmin, cache, SERVICE);

        Health first = indicator.health();
        Health second = indicator.health();

        assertThat(first.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(second.getStatus().getCode()).isEqualTo("DOWN");
        // The indicator delegates entirely to the cache each call — it never touches an
        // ApplicationContext or performs its own bean/reflection scan.
        verify(cache, times(2)).handledTypeMappings();
    }
}
