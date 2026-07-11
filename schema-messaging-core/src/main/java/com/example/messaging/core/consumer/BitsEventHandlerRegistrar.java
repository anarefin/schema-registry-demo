package com.example.messaging.core.consumer;

import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Discovers every {@link BitsEventHandler}-annotated method across all singleton beans and
 * registers it as a listener endpoint programmatically — {@code @RabbitListener}'s {@code queues}
 * attribute must resolve to a compile-time constant/SpEL expression at bootstrap, so a bare event
 * type on the method signature can't drive it directly (spec
 * {@code simplified-publish-and-listen.md} D3).
 *
 * <p>The queue name is derived from the handler's single parameter type via
 * {@link TypeMappingRegistry} + {@link TopologyNaming#queueName(String)} — the same convention
 * the topology auto-configurations already use — and every endpoint is registered against the
 * shared {@code rabbitListenerContainerFactory} bean, so it inherits the same DLQ/retry advice
 * chain and message converter as every other listener.
 */
public class BitsEventHandlerRegistrar implements RabbitListenerConfigurer {

    private static final String CONTAINER_FACTORY_BEAN_NAME = "rabbitListenerContainerFactory";

    private final ApplicationContext applicationContext;
    private final TypeMappingRegistry typeMappingRegistry;

    public BitsEventHandlerRegistrar(
            ApplicationContext applicationContext,
            TypeMappingRegistry typeMappingRegistry) {
        this.applicationContext = applicationContext;
        this.typeMappingRegistry = typeMappingRegistry;
    }

    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
        @SuppressWarnings("unchecked")
        RabbitListenerContainerFactory<?> containerFactory =
                applicationContext.getBean(CONTAINER_FACTORY_BEAN_NAME, RabbitListenerContainerFactory.class);

        DefaultMessageHandlerMethodFactory handlerMethodFactory = new DefaultMessageHandlerMethodFactory();
        handlerMethodFactory.setBeanFactory(applicationContext.getAutowireCapableBeanFactory());
        handlerMethodFactory.afterPropertiesSet();

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = bean.getClass();

            Set<Method> handlerMethods = MethodIntrospector.selectMethods(targetClass,
                    (ReflectionUtils.MethodFilter) method ->
                            AnnotatedElementUtils.hasAnnotation(method, BitsEventHandler.class));

            for (Method method : handlerMethods) {
                registerEndpoint(registrar, containerFactory, handlerMethodFactory, beanName, bean, method);
            }
        }
    }

    private void registerEndpoint(
            RabbitListenerEndpointRegistrar registrar,
            RabbitListenerContainerFactory<?> containerFactory,
            DefaultMessageHandlerMethodFactory handlerMethodFactory,
            String beanName,
            Object bean,
            Method method) {
        if (method.getParameterCount() != 1) {
            throw new IllegalStateException(
                    "@BitsEventHandler method " + method + " must have exactly one parameter (the event type)");
        }
        Class<?> eventType = method.getParameterTypes()[0];
        TypeMapping mapping = typeMappingRegistry.findByJavaType(eventType)
                .orElseThrow(() -> new IllegalStateException(
                        "No TypeMapping for " + eventType.getName()));

        String queue = TopologyNaming.queueName(mapping.routingKey());

        MethodRabbitListenerEndpoint endpoint = new MethodRabbitListenerEndpoint();
        endpoint.setBean(bean);
        endpoint.setMethod(method);
        endpoint.setId(beanName + "#" + method.getName());
        endpoint.setQueueNames(queue);
        endpoint.setMessageHandlerMethodFactory(handlerMethodFactory);

        registrar.registerEndpoint(endpoint, containerFactory);
    }
}
