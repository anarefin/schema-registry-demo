package com.example.messaging.core.consumer;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BitsEventHandlerScannerTest {

    record DemoEvent(String id) {}

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
    void discoverHandledTypeMappings_findsHandlersOnJdkAopProxy() {
        TypeMapping mapping = new TypeMapping(
                DemoEvent.class,
                new SchemaCoordinates("events.demo", "DemoEvent"),
                SchemaType.JSON,
                "demo.event",
                "events.demo.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));

        Object proxy = jdkProxy(new ProxiedHandler());
        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeanDefinitionNames()).thenReturn(new String[] {"proxiedHandler"});
        when(ctx.getType("proxiedHandler")).thenAnswer(invocation -> ProxiedHandler.class);

        Set<TypeMapping> handled = BitsEventHandlerScanner.discoverHandledTypeMappings(ctx, registry);

        assertThat(handled).containsExactly(mapping);
        verify(ctx, never()).getBean("proxiedHandler");
    }

    @Test
    void discoverHandledTypeMappings_doesNotInstantiateNonHandlerBeans() {
        TypeMapping mapping = new TypeMapping(
                DemoEvent.class,
                new SchemaCoordinates("events.demo", "DemoEvent"),
                SchemaType.JSON,
                "demo.event",
                "events.demo.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeanDefinitionNames()).thenReturn(new String[] {"handler", "noiseBean"});
        when(ctx.getType("handler")).thenAnswer(invocation -> Handler.class);
        when(ctx.getType("noiseBean")).thenAnswer(invocation -> NoiseBean.class);

        Set<TypeMapping> handled = BitsEventHandlerScanner.discoverHandledTypeMappings(ctx, registry);

        assertThat(handled).containsExactly(mapping);
        verify(ctx, never()).getBean("handler");
        verify(ctx, never()).getBean("noiseBean");
    }

    private static Object jdkProxy(HandlerApi target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(false);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        return factory.getProxy();
    }
}
