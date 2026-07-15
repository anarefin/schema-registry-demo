package com.example.messaging.core.config;

import com.example.messaging.core.consumer.BitsEventHandlerScanner;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Gates consumer listener infrastructure on {@code @BitsEventHandler} presence — the same
 * discovery signal topology declaration and health checks already use.
 */
public class OnBitsEventHandlerPresentCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory == null) {
            return false;
        }
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType = beanFactory.getType(beanName);
            if (beanType == null) {
                continue;
            }
            if (!BitsEventHandlerScanner.handlerMethods(BitsEventHandlerScanner.targetClass(beanType)).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
