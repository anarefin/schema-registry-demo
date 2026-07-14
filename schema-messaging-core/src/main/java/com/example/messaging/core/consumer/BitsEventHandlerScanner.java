package com.example.messaging.core.consumer;

import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    public record HandlerBinding(String beanName, Set<Method> methods) {}

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
     * Resolves the class to scan for {@link BitsEventHandler} methods from a bean type without
     * instantiating the bean. Unwraps CGLIB-generated subclasses so annotations on the real
     * target are visible.
     */
    public static Class<?> targetClass(Class<?> beanType) {
        return ClassUtils.getUserClass(beanType);
    }

    /**
     * Resolves the class to scan for {@link BitsEventHandler} methods. Unwraps Spring AOP
     * proxies (JDK and CGLIB) so annotations on the real target are visible — same defensive
     * step Spring's {@code EventListenerMethodProcessor} takes before an equivalent method scan.
     */
    public static Class<?> targetClass(Object bean) {
        return AopUtils.getTargetClass(bean);
    }

    public static List<HandlerBinding> discoverHandlerBindings(ApplicationContext applicationContext) {
        List<HandlerBinding> bindings = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null) {
                continue;
            }
            Set<Method> methods = handlerMethods(targetClass(beanType));
            if (!methods.isEmpty()) {
                bindings.add(new HandlerBinding(beanName, methods));
            }
        }
        return bindings;
    }

    public static Set<TypeMapping> discoverHandledTypeMappings(
            ApplicationContext applicationContext, TypeMappingRegistry typeMappingRegistry) {
        Set<TypeMapping> mappings = new LinkedHashSet<>();
        for (HandlerBinding binding : discoverHandlerBindings(applicationContext)) {
            for (Method method : binding.methods()) {
                mappings.add(mappingFor(typeMappingRegistry, method));
            }
        }
        return mappings;
    }
}
