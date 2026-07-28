package com.example.messaging.core.consumer;

import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BitsEventHandlerRegistrarTest {

    private static final String SERVICE = "consumer-service";

    static final class DemoEvent {
        private final String id;

        DemoEvent(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }
    }

    interface HandlerApi {
        void onDemo(DemoEvent event);
    }

    static class ProxiedHandler implements HandlerApi {
        @BitsEventHandler
        @Override
        public void onDemo(DemoEvent event) {}
    }

    static class Handler {
        @BitsEventHandler
        public void onDemo(DemoEvent event) {}
    }

    static class NoiseBean {}

    @Test
    void configureRabbitListeners_registersEndpointForJdkAopProxy() {
        TypeMapping mapping = new TypeMapping(
                DemoEvent.class,
                new SchemaCoordinates("events.demo", "DemoEvent"),
                SchemaType.JSON,
                "demo.event",
                "events.demo.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));

        Object proxy = jdkProxy(new ProxiedHandler());
        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();

        RabbitListenerContainerFactory<?> containerFactory = mock(RabbitListenerContainerFactory.class);
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("rabbitListenerContainerFactory", RabbitListenerContainerFactory.class))
                .thenReturn(containerFactory);
        when(ctx.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(ctx.getBeanDefinitionNames()).thenReturn(new String[] {"proxiedHandler"});
        when(ctx.getType("proxiedHandler")).thenAnswer(invocation -> ProxiedHandler.class);
        when(ctx.getBean("proxiedHandler")).thenReturn(proxy);

        RabbitListenerEndpointRegistrar endpointRegistrar = mock(RabbitListenerEndpointRegistrar.class);
        BitsEventHandlerRegistrar registrar = new BitsEventHandlerRegistrar(ctx, registry, SERVICE);

        registrar.configureRabbitListeners(endpointRegistrar);

        ArgumentCaptor<RabbitListenerEndpoint> endpointCaptor =
                ArgumentCaptor.forClass(RabbitListenerEndpoint.class);
        verify(endpointRegistrar).registerEndpoint(endpointCaptor.capture(), eq(containerFactory));

        MethodRabbitListenerEndpoint endpoint = (MethodRabbitListenerEndpoint) endpointCaptor.getValue();
        assertThat(endpoint.getId()).isEqualTo("proxiedHandler#onDemo");
        assertThat(endpoint.getQueueNames()).containsExactly(
                TopologyNaming.serviceQueueName("demo.event", SERVICE));
        assertThat(endpoint.getBean()).isSameAs(proxy);
    }

    @Test
    void configureRabbitListeners_doesNotInstantiateNonHandlerBeans() {
        TypeMapping mapping = new TypeMapping(
                DemoEvent.class,
                new SchemaCoordinates("events.demo", "DemoEvent"),
                SchemaType.JSON,
                "demo.event",
                "events.demo.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));

        Object handler = new Handler();

        RabbitListenerContainerFactory<?> containerFactory = mock(RabbitListenerContainerFactory.class);
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("rabbitListenerContainerFactory", RabbitListenerContainerFactory.class))
                .thenReturn(containerFactory);
        when(ctx.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(ctx.getBeanDefinitionNames()).thenReturn(new String[] {"handler", "noiseBean"});
        when(ctx.getType("handler")).thenAnswer(invocation -> Handler.class);
        when(ctx.getType("noiseBean")).thenAnswer(invocation -> NoiseBean.class);
        when(ctx.getBean("handler")).thenReturn(handler);

        RabbitListenerEndpointRegistrar endpointRegistrar = mock(RabbitListenerEndpointRegistrar.class);
        BitsEventHandlerRegistrar registrar = new BitsEventHandlerRegistrar(ctx, registry, SERVICE);

        registrar.configureRabbitListeners(endpointRegistrar);

        verify(ctx).getBean("handler");
        verify(ctx, never()).getBean("noiseBean");
    }

    private static Object jdkProxy(HandlerApi target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(false);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        return factory.getProxy();
    }
}
