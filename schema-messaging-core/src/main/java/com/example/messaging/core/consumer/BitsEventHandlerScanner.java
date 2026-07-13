package com.example.messaging.core.consumer;

import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared {@link BitsEventHandler} discovery used by every component that needs to know which
 * event types this service actually handles: {@link BitsEventHandlerRegistrar} (registers the
 * listener endpoints), {@code ServiceQueueTopologyAutoConfiguration} (declares the per-service
 * queues those endpoints listen on), and {@code QueueDepthHealthIndicator} (reports depth only
 * for queues that were actually declared). Keeping the scan in one place means these three can't
 * drift apart on which types count as "handled".
 */
public final class BitsEventHandlerScanner {

    private BitsEventHandlerScanner() {}

    public static Set<Method> handlerMethods(Class<?> targetClass) {
        return MethodIntrospector.selectMethods(targetClass,
                (ReflectionUtils.MethodFilter) method ->
                        AnnotatedElementUtils.hasAnnotation(method, BitsEventHandler.class));
    }

    public static TypeMapping mappingFor(TypeMappingRegistry typeMappingRegistry, Method method) {
        if (method.getParameterCount() != 1) {
            throw new IllegalStateException(
                    "@BitsEventHandler method " + method + " must have exactly one parameter (the event type)");
        }
        Class<?> eventType = method.getParameterTypes()[0];
        return typeMappingRegistry.findByJavaType(eventType)
                .orElseThrow(() -> new IllegalStateException(
                        "No TypeMapping for " + eventType.getName()));
    }

    /**
     * Resolves the class to scan for {@link BitsEventHandler} methods. Unwraps Spring AOP
     * proxies (JDK and CGLIB) so annotations on the real target are visible — same defensive
     * step Spring's {@code EventListenerMethodProcessor} takes before an equivalent method scan.
     */
    public static Class<?> targetClass(Object bean) {
        return AopUtils.getTargetClass(bean);
    }

    public static Set<TypeMapping> discoverHandledTypeMappings(
            ApplicationContext applicationContext, TypeMappingRegistry typeMappingRegistry) {
        Set<TypeMapping> mappings = new LinkedHashSet<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> typeToScan = BitsEventHandlerScanner.targetClass(bean);
            for (Method method : handlerMethods(typeToScan)) {
                mappings.add(mappingFor(typeMappingRegistry, method));
            }
        }
        return mappings;
    }
}
