package com.example.amqp.topology.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.contractkit.indexfixtures.alpha.AlphaCreated;

/**
 * Spring-level proof of the registrar path: the {@code @RegisterEventMappings} import turns the
 * build-time index into named {@link TypeMapping} beans, honouring exact-package scoping, the
 * locked bean-name algorithm, and application overrides.
 */
class EventMappingRegistrarTest {

    private static final String ALPHA_CREATED = "com.example.contractkit.indexfixtures.alpha.AlphaCreated";
    private static final String BETA_SHIPPED = "com.example.contractkit.indexfixtures.alpha.BetaShipped";
    private static final String SUB_EVENT = "com.example.contractkit.indexfixtures.alpha.sub.SubEvent";
    private static final String GAMMA_REGISTERED = "com.example.contractkit.indexfixtures.beta.GammaRegistered";

    @Test
    void registersNamedTypeMappingBeansForTheTargetPackage(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED, BETA_SHIPPED, SUB_EVENT);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            new ApplicationContextRunner()
                    .withClassLoader(cl)
                    .withUserConfiguration(AlphaConfig.class)
                    .run(context -> {
                        // Locked bean names; exact-package scoping excludes SubEvent.
                        assertThat(context.getBeanNamesForType(TypeMapping.class))
                                .containsExactlyInAnyOrder("alphaCreatedMapping", "betaShippedMapping");

                        TypeMapping alpha = (TypeMapping) context.getBean("alphaCreatedMapping");
                        assertThat(alpha.javaType()).isEqualTo(AlphaCreated.class);
                        assertThat(alpha.coordinates())
                                .isEqualTo(new SchemaCoordinates("events.alpha", "AlphaCreated"));
                        assertThat(alpha.routingKey()).isEqualTo("alpha.created");
                    });
        }
    }

    @Test
    void registersOnlyTheTargetPackageInAMultiJarClasspath(@TempDir Path jarA, @TempDir Path jarB) throws Exception {
        IndexClassLoaders.writeIndex(jarA, ALPHA_CREATED, BETA_SHIPPED);
        IndexClassLoaders.writeIndex(jarB, GAMMA_REGISTERED);

        try (URLClassLoader cl = IndexClassLoaders.over(jarA, jarB)) {
            new ApplicationContextRunner()
                    .withClassLoader(cl)
                    .withUserConfiguration(AlphaConfig.class)
                    .run(context -> assertThat(context.getBeanNamesForType(TypeMapping.class))
                            // GammaRegistered (events.beta) is on the classpath but not this package.
                            .containsExactlyInAnyOrder("alphaCreatedMapping", "betaShippedMapping"));
        }
    }

    @Test
    void applicationBeanDefinitionWinsWhileOthersStillRegister(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED, BETA_SHIPPED);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            new ApplicationContextRunner()
                    .withClassLoader(cl)
                    .withUserConfiguration(AlphaConfigWithOverride.class)
                    .run(context -> {
                        assertThat(context.getBeanNamesForType(TypeMapping.class))
                                .containsExactlyInAnyOrder("alphaCreatedMapping", "betaShippedMapping");
                        // The app's override wins; the generated alphaCreatedMapping was skipped.
                        TypeMapping overridden = (TypeMapping) context.getBean("alphaCreatedMapping");
                        assertThat(overridden.routingKey()).isEqualTo("app.override");
                        // The other generated mapping still registered.
                        assertThat(context.containsBean("betaShippedMapping")).isTrue();
                    });
        }
    }

    @Test
    void failsFastWhenTargetPackageMatchesNoIndexedEvent(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, GAMMA_REGISTERED); // only events.beta present

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            new ApplicationContextRunner()
                    .withClassLoader(cl)
                    .withUserConfiguration(AlphaConfig.class) // asks for the empty ...alpha package
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .isInstanceOf(EventMappingRegistrationException.class)
                            .hasMessageContaining("No indexed @EventMapping events found"));
        }
    }

    @Test
    void failsFastWhenPackageIsBlank(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            new ApplicationContextRunner()
                    .withClassLoader(cl)
                    .withUserConfiguration(BlankPackageConfig.class)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .isInstanceOf(EventMappingRegistrationException.class)
                            .hasMessageContaining("blank package"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @RegisterEventMappings("com.example.contractkit.indexfixtures.alpha")
    static class AlphaConfig {}

    @Configuration(proxyBeanMethods = false)
    @RegisterEventMappings("")
    static class BlankPackageConfig {}

    @Configuration(proxyBeanMethods = false)
    @RegisterEventMappings("com.example.contractkit.indexfixtures.alpha")
    static class AlphaConfigWithOverride {

        @Bean("alphaCreatedMapping")
        TypeMapping alphaCreatedMapping() {
            return Mappings.forDomain("events.alpha", "events.alpha.exchange")
                    .json(AlphaCreated.class, "app.override");
        }
    }
}
