package com.example.amqp.topology.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.ref.WeakReference;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexedEventMappingsTest {

    private static final String ALPHA_CREATED = "com.example.contractkit.indexfixtures.alpha.AlphaCreated";
    private static final String BETA_SHIPPED = "com.example.contractkit.indexfixtures.alpha.BetaShipped";
    private static final String AVRO_ONLY = "com.example.contractkit.indexfixtures.alpha.AvroOnlyEvent";
    private static final String SUB_EVENT = "com.example.contractkit.indexfixtures.alpha.sub.SubEvent";
    private static final String GAMMA_REGISTERED = "com.example.contractkit.indexfixtures.beta.GammaRegistered";
    private static final String CLASH_ONE = "com.example.contractkit.indexfixtures.clash.ClashOne";
    private static final String CLASH_TWO = "com.example.contractkit.indexfixtures.clash.ClashTwo";
    private static final String NOT_AN_EVENT = "com.example.contractkit.indexfixtures.plain.NotAnEvent";

    @Test
    void buildsTypeMappingsViaMappingsWithDefaultAndExplicitArtifactIds(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED, BETA_SHIPPED);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            List<IndexedEventMappings.Entry> all = IndexedEventMappings.load(cl).all();
            assertThat(all).hasSize(2);

            TypeMapping alpha = mappingFor(all, "AlphaCreated");
            // Blank annotation artifactId defaults to the simple name.
            assertThat(alpha.coordinates()).isEqualTo(new SchemaCoordinates("events.alpha", "AlphaCreated"));
            assertThat(alpha.exchange()).isEqualTo("events.alpha.exchange");
            assertThat(alpha.routingKey()).isEqualTo("alpha.created");
            assertThat(alpha.schemaType()).isEqualTo(SchemaType.JSON);

            TypeMapping beta = mappingFor(all, "BetaShipped");
            // Explicit annotation artifactId is honoured.
            assertThat(beta.coordinates()).isEqualTo(new SchemaCoordinates("events.alpha", "BetaShippedV1"));
            assertThat(beta.routingKey()).isEqualTo("alpha.shipped");
        }
    }

    @Test
    void filtersByExactPackageEqualityNotPrefix(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED, BETA_SHIPPED, SUB_EVENT);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            IndexedEventMappings catalog = IndexedEventMappings.load(cl);
            List<String> alphaTypes = catalog.inPackage("com.example.contractkit.indexfixtures.alpha")
                    .stream().map(e -> e.javaType().getSimpleName()).toList();
            // SubEvent lives in ...alpha.sub and must NOT be swept in by the parent package.
            assertThat(alphaTypes).containsExactlyInAnyOrder("AlphaCreated", "BetaShipped");
        }
    }

    @Test
    void mergesAcrossJarsThenValidatesGlobally(@TempDir Path jarA, @TempDir Path jarB) throws Exception {
        IndexClassLoaders.writeIndex(jarA, ALPHA_CREATED);
        IndexClassLoaders.writeIndex(jarB, GAMMA_REGISTERED);

        try (URLClassLoader cl = IndexClassLoaders.over(jarA, jarB)) {
            List<String> simpleNames = IndexedEventMappings.load(cl).all()
                    .stream().map(e -> e.javaType().getSimpleName()).toList();
            assertThat(simpleNames).containsExactlyInAnyOrder("AlphaCreated", "GammaRegistered");
        }
    }

    @Test
    void failsOnAnnotationIndexMismatch(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED, NOT_AN_EVENT);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            assertThatThrownBy(() -> IndexedEventMappings.load(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("not annotated with @EventMapping");
        }
    }

    @Test
    void failsWhenIndexedClassCannotBeLoaded(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, "com.example.contractkit.indexfixtures.DoesNotExist");

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            assertThatThrownBy(() -> IndexedEventMappings.load(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("could not be loaded");
        }
    }

    @Test
    void failsOnBeanNameCollisionAcrossJars(@TempDir Path jarA, @TempDir Path jarB) throws Exception {
        // Distinct coordinates but identical simple name => identical deterministic bean name.
        IndexClassLoaders.writeIndex(jarA, "com.example.contractkit.indexfixtures.dupname.StatusChanged");
        IndexClassLoaders.writeIndex(jarB, "com.example.contractkit.indexfixtures.dupname.other.StatusChanged");

        try (URLClassLoader cl = IndexClassLoaders.over(jarA, jarB)) {
            assertThatThrownBy(() -> IndexedEventMappings.load(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("Duplicate bean name 'statusChangedMapping'");
        }
    }

    @Test
    void failsOnDuplicateCoordinatesAcrossJars(@TempDir Path jarA, @TempDir Path jarB) throws Exception {
        IndexClassLoaders.writeIndex(jarA, CLASH_ONE);
        IndexClassLoaders.writeIndex(jarB, CLASH_TWO);

        try (URLClassLoader cl = IndexClassLoaders.over(jarA, jarB)) {
            assertThatThrownBy(() -> IndexedEventMappings.load(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("Duplicate schema coordinates");
        }
    }

    @Test
    void failsOnUnsupportedSchemaType(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, AVRO_ONLY);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            assertThatThrownBy(() -> IndexedEventMappings.load(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("Unsupported @EventMapping.schemaType=AVRO")
                    .hasMessageContaining("only SchemaType.JSON");
        }
    }

    @Test
    void forClassLoaderReturnsSameInstanceOnRepeatedSameLoaderCalls(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED, BETA_SHIPPED);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            IndexedEventMappings first = IndexedEventMappings.forClassLoader(cl);
            IndexedEventMappings second = IndexedEventMappings.forClassLoader(cl);
            // Memoized: the merged, validated index is built once per loader.
            assertThat(second).isSameAs(first);
        }
    }

    @Test
    void forClassLoaderGivesDistinctLoadersDistinctInstances(@TempDir Path jarA, @TempDir Path jarB) throws Exception {
        IndexClassLoaders.writeIndex(jarA, ALPHA_CREATED);
        IndexClassLoaders.writeIndex(jarB, ALPHA_CREATED);

        try (URLClassLoader clA = IndexClassLoaders.over(jarA);
             URLClassLoader clB = IndexClassLoaders.over(jarB)) {
            assertThat(IndexedEventMappings.forClassLoader(clA))
                    .isNotSameAs(IndexedEventMappings.forClassLoader(clB));
        }
    }

    @Test
    void forClassLoaderIsContentEqualToFreshLoad(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED, BETA_SHIPPED);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            List<IndexedEventMappings.Entry> memoized = IndexedEventMappings.forClassLoader(cl).all();
            List<IndexedEventMappings.Entry> fresh = IndexedEventMappings.load(cl).all();
            assertThat(memoized).isEqualTo(fresh);
        }
    }

    @Test
    void forClassLoaderDoesNotCacheFailures(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED, NOT_AN_EVENT);

        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            assertThatThrownBy(() -> IndexedEventMappings.forClassLoader(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("not annotated with @EventMapping");
            // A throwing load stores nothing; a later call re-throws the same deterministic error.
            assertThatThrownBy(() -> IndexedEventMappings.forClassLoader(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("not annotated with @EventMapping");
        }
    }

    @Test
    void forClassLoaderDoesNotPinAClosedClassLoader(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndex(jar, ALPHA_CREATED);

        URLClassLoader cl = IndexClassLoaders.over(jar);
        IndexedEventMappings.forClassLoader(cl);
        WeakReference<URLClassLoader> ref = new WeakReference<>(cl);
        cl.close();
        cl = null;

        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        // Weak keys: the cache must not pin a GC-eligible (per-test / hot-reload) URLClassLoader.
        assertThat(ref.get()).isNull();
    }

    private static TypeMapping mappingFor(List<IndexedEventMappings.Entry> entries, String simpleName) {
        return entries.stream()
                .filter(e -> e.javaType().getSimpleName().equals(simpleName))
                .map(IndexedEventMappings.Entry::mapping)
                .findFirst()
                .orElseThrow();
    }
}
