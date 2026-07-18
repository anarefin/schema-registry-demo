package com.example.amqp.topology.mapping;

import java.util.List;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers one named {@link TypeMapping} bean per indexed {@link EventMapping} record in the
 * package named by the importing class's {@link RegisterEventMappings}. Loads and validates the
 * whole build-time index via {@link IndexedEventMappings} (so schema-coordinate collisions and
 * annotation/index drift across <em>any</em> jar fail startup), then registers only the events in
 * the requested package.
 *
 * <p>Bean names come from the locked {@link MappingBeanNames} algorithm. Registration is skipped
 * when {@link BeanDefinitionRegistry#containsBeanDefinition(String)} already holds that name, so an
 * application's own {@code @Bean} definition wins while every other generated mapping still
 * registers. Reached only through an {@code @AutoConfiguration} import, so user bean definitions are
 * already present when this runs — the same "app override wins" semantics the hand-written
 * {@code @ConditionalOnMissingBean} methods had.
 *
 * <p>An import whose package matches no indexed event fails fast: a contracts jar is expected to
 * carry its index, so an empty match means a missing/mis-built index, not a valid empty domain.
 */
public final class EventMappingRegistrar implements ImportBeanDefinitionRegistrar, BeanClassLoaderAware {

    private ClassLoader classLoader;

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        String targetPackage = targetPackage(importingClassMetadata);
        ClassLoader loader = classLoader != null ? classLoader : getClass().getClassLoader();

        List<IndexedEventMappings.Entry> events = IndexedEventMappings.load(loader).inPackage(targetPackage);
        if (events.isEmpty()) {
            throw new EventMappingRegistrationException(
                    "No indexed @EventMapping events found in package " + targetPackage
                            + " (expected a " + EventMappingIndexReader.INDEX_RESOURCE_PATH
                            + " listing at least one record — is the contracts index missing?)");
        }

        for (IndexedEventMappings.Entry event : events) {
            String beanName = MappingBeanNames.forType(event.javaType());
            if (registry.containsBeanDefinition(beanName)) {
                continue; // application override wins
            }
            TypeMapping mapping = event.mapping();
            registry.registerBeanDefinition(
                    beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(TypeMapping.class, () -> mapping)
                            .getBeanDefinition());
        }
    }

    private static String targetPackage(AnnotationMetadata metadata) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(RegisterEventMappings.class.getName()));
        if (attributes == null) {
            throw new EventMappingRegistrationException(
                    "@RegisterEventMappings not found on " + metadata.getClassName()
                            + " (EventMappingRegistrar must be imported through it)");
        }
        String targetPackage = attributes.getString("value");
        if (targetPackage.isBlank()) {
            throw new EventMappingRegistrationException(
                    "@RegisterEventMappings on " + metadata.getClassName()
                            + " has a blank package — specify the exact event package to register");
        }
        return targetPackage;
    }
}
