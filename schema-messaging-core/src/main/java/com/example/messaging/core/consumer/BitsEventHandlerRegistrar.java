package com.example.messaging.core.consumer;

import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;

import java.lang.reflect.Method;

/**
 * Discovers every {@link BitsEventHandler}-annotated method across all singleton beans and
 * registers it as a listener endpoint programmatically — {@code @RabbitListener}'s {@code queues}
 * attribute must resolve to a compile-time constant/SpEL expression at bootstrap, so a bare event
 * type on the method signature can't drive it directly (spec
 * {@code simplified-publish-and-listen.md} D3).
 *
 * <p>The queue name is derived from the handler's single parameter type via
 * {@link TypeMappingRegistry} + {@link TopologyNaming#serviceQueueName(String, String)} —
 * the same convention the service topology auto-configuration uses — and every endpoint is
 * shared {@code rabbitListenerContainerFactory} bean, so it inherits the same DLQ/retry advice
 * chain and message converter as every other listener.
 */
public class BitsEventHandlerRegistrar implements RabbitListenerConfigurer {

    private static final String CONTAINER_FACTORY_BEAN_NAME = "rabbitListenerContainerFactory";

    private final ApplicationContext applicationContext;
    private final TypeMappingRegistry typeMappingRegistry;
    private final String serviceName;

    public BitsEventHandlerRegistrar(
            ApplicationContext applicationContext,
            TypeMappingRegistry typeMappingRegistry,
            String serviceName) {
        this.applicationContext = applicationContext;
        this.typeMappingRegistry = typeMappingRegistry;
        this.serviceName = serviceName;
    }

    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
        @SuppressWarnings("unchecked")
        RabbitListenerContainerFactory<?> containerFactory =
                applicationContext.getBean(CONTAINER_FACTORY_BEAN_NAME, RabbitListenerContainerFactory.class);

        DefaultMessageHandlerMethodFactory handlerMethodFactory = new DefaultMessageHandlerMethodFactory();
        handlerMethodFactory.setBeanFactory(applicationContext.getAutowireCapableBeanFactory());
        handlerMethodFactory.afterPropertiesSet();

        for (BitsEventHandlerScanner.HandlerBinding binding :
                BitsEventHandlerScanner.discoverHandlerBindings(applicationContext)) {
            Object bean = applicationContext.getBean(binding.getBeanName());
            for (Method method : binding.getMethods()) {
                registerEndpoint(
                        registrar, containerFactory, handlerMethodFactory, binding.getBeanName(), bean, method);
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
        TypeMapping mapping = BitsEventHandlerScanner.mappingFor(typeMappingRegistry, method);
        String queue = TopologyNaming.serviceQueueName(mapping.getRoutingKey(), serviceName);
        Method invocableMethod = AopUtils.selectInvocableMethod(method, bean.getClass());

        MethodRabbitListenerEndpoint endpoint = new MethodRabbitListenerEndpoint();
        endpoint.setBean(bean);
        endpoint.setMethod(invocableMethod);
        endpoint.setId(beanName + "#" + method.getName());
        endpoint.setQueueNames(queue);
        endpoint.setMessageHandlerMethodFactory(handlerMethodFactory);

        registrar.registerEndpoint(endpoint, containerFactory);
    }
}
