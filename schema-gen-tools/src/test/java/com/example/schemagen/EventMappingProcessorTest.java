package com.example.schemagen;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link EventMappingProcessor} by running it over in-memory {@code @EventMapping} sources
 * with an in-JVM {@code javac} in annotation-processing-only mode ({@code -proc:only}), then asserts
 * both the generated source content and the {@link Diagnostic.Kind#ERROR} diagnostics.
 *
 * <p>{@code ToolProvider.getSystemJavaCompiler()} (not {@code compile-testing}) is used deliberately:
 * it adds no dependency, and {@code -proc:only} isolates the processor from downstream compilation of
 * the emitted source — so the test needs neither {@code event-contract-kit} nor Spring on the
 * classpath (keeping this build-only module dependency-free per ADR-0003).
 */
class EventMappingProcessorTest {

    private static final String EVENT_MAPPING = """
            package com.example.amqp.topology.mapping;
            public @interface EventMapping {
                String groupId();
                String exchange();
                String routingKey();
                String artifactId() default "";
                SchemaType schemaType() default SchemaType.JSON;
            }
            """;

    private static final String SCHEMA_TYPE = """
            package com.example.amqp.topology.mapping;
            public enum SchemaType { JSON, AVRO }
            """;

    // Minimal API stand-ins so the emitted @Configuration actually compiles against the shapes it
    // calls — mirroring the real event-contract-kit + Spring types the contracts modules provide.
    // Keeping them in-memory preserves this build-only module's freedom from a compile dep on the kit.
    private static final String MAPPINGS = """
            package com.example.amqp.topology.mapping;
            public final class Mappings {
                private Mappings() {}
                public static Mappings forDomain(String group, String exchange) { return new Mappings(); }
                public TypeMapping json(Class<?> type, String artifactId, String routingKey) { return new TypeMapping(); }
            }
            """;

    private static final String TYPE_MAPPING = """
            package com.example.amqp.topology.mapping;
            public final class TypeMapping {}
            """;

    private static final String CONFIGURATION = """
            package org.springframework.context.annotation;
            public @interface Configuration {}
            """;

    private static final String BEAN = """
            package org.springframework.context.annotation;
            public @interface Bean {}
            """;

    private static final String CONDITIONAL_ON_MISSING_BEAN = """
            package org.springframework.boot.autoconfigure.condition;
            public @interface ConditionalOnMissingBean { String[] name() default {}; }
            """;

    private static final String ROUTING = """
            package demo.events;
            public final class Routing {
                private Routing() {}
                public static final String EXCHANGE = "events.demo.exchange";
                public static final String CREATED_ROUTING_KEY = "demo.created";
            }
            """;

    private static final String GENERATED_FQCN = "demo.events.topology.GeneratedEventTypeMappings";

    @Test
    void emitsSortedConfigurationWithResolvedConstantsAndDefaultedArtifactId() {
        // OrderCreated references routing constants + defaults its artifactId to the simple name;
        // OrderShipped uses literals + an explicit artifactId. FQCN sort puts OrderCreated first.
        String orderCreated = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.demo",
                        exchange = Routing.EXCHANGE,
                        routingKey = Routing.CREATED_ROUTING_KEY)
                public record OrderCreated(String id) {}
                """;
        String orderShipped = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.demo",
                        exchange = "events.demo.exchange",
                        routingKey = "demo.shipped",
                        artifactId = "OrderShippedV1")
                public record OrderShipped(String id) {}
                """;

        Result result = compile(
                source("demo.events.Routing", ROUTING),
                source("demo.events.OrderCreated", orderCreated),
                source("demo.events.OrderShipped", orderShipped));

        assertThat(result.errors()).isEmpty();
        String generated = result.generated.get(GENERATED_FQCN);
        assertThat(generated).isNotNull();

        assertThat(generated).contains("package demo.events.topology;");
        assertThat(generated).contains("import com.example.amqp.topology.mapping.Mappings;");
        assertThat(generated).contains("import com.example.amqp.topology.mapping.TypeMapping;");
        assertThat(generated).contains(
                "import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;");
        assertThat(generated).contains("import org.springframework.context.annotation.Bean;");
        assertThat(generated).contains("import org.springframework.context.annotation.Configuration;");
        assertThat(generated).contains("@Configuration");
        assertThat(generated).contains("public class GeneratedEventTypeMappings {");

        // OrderCreated: constants resolved to their string values, artifactId defaulted to simple name.
        assertThat(generated).contains("""
                    @Bean
                    @ConditionalOnMissingBean(name = "orderCreatedMapping")
                    public TypeMapping orderCreatedMapping() {
                        return Mappings.forDomain("events.demo", "events.demo.exchange")
                                .json(demo.events.OrderCreated.class, "OrderCreated", "demo.created");
                    }
                """);
        // OrderShipped: literal exchange + explicit artifactId.
        assertThat(generated).contains("""
                    @Bean
                    @ConditionalOnMissingBean(name = "orderShippedMapping")
                    public TypeMapping orderShippedMapping() {
                        return Mappings.forDomain("events.demo", "events.demo.exchange")
                                .json(demo.events.OrderShipped.class, "OrderShippedV1", "demo.shipped");
                    }
                """);

        // Byte-stable FQCN order: OrderCreated before OrderShipped.
        assertThat(generated.indexOf("orderCreatedMapping"))
                .isLessThan(generated.indexOf("orderShippedMapping"));
    }

    @Test
    void failsOnBlankGroupId() {
        String source = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "", exchange = "e", routingKey = "r")
                public record BlankGroup(String id) {}
                """;
        Result result = compile(source("demo.events.BlankGroup", source));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("groupId").contains("non-blank"));
        assertThat(result.generated).doesNotContainKey(GENERATED_FQCN);
    }

    @Test
    void failsOnNonJsonSchemaType() {
        String source = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                import com.example.amqp.topology.mapping.SchemaType;
                @EventMapping(groupId = "g", exchange = "e", routingKey = "r",
                        schemaType = SchemaType.AVRO)
                public record AvroEvent(String id) {}
                """;
        Result result = compile(source("demo.events.AvroEvent", source));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("schemaType").contains("JSON").contains("AVRO"));
        assertThat(result.generated).doesNotContainKey(GENERATED_FQCN);
    }

    @Test
    void failsOnDuplicateCoordinates() {
        String dupA = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "g", exchange = "e", routingKey = "r1", artifactId = "Same")
                public record DupA(String id) {}
                """;
        String dupB = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "g", exchange = "e", routingKey = "r2", artifactId = "Same")
                public record DupB(String id) {}
                """;
        Result result = compile(
                source("demo.events.DupA", dupA),
                source("demo.events.DupB", dupB));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("Duplicate effective schema coordinates").contains("Same"));
        assertThat(result.generated).doesNotContainKey(GENERATED_FQCN);
    }

    @Test
    void failsOnDuplicateBeanName() {
        // Same simple name in different packages + different groups: coordinates differ, bean name clashes.
        String widgetA = """
                package demo.events.a;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "g.a", exchange = "e", routingKey = "r")
                public record Widget(String id) {}
                """;
        String widgetB = """
                package demo.events.b;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "g.b", exchange = "e", routingKey = "r")
                public record Widget(String id) {}
                """;
        Result result = compile(
                source("demo.events.a.Widget", widgetA),
                source("demo.events.b.Widget", widgetB));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("Duplicate mapping bean name").contains("widgetMapping"));
    }

    // ---- in-JVM compilation harness ----

    /** The in-memory {@code @EventMapping}/{@code SchemaType} + {@code Mappings}/Spring stand-ins. */
    private static List<JavaFileObject> stubs() {
        return new ArrayList<>(List.of(
                source("com.example.amqp.topology.mapping.EventMapping", EVENT_MAPPING),
                source("com.example.amqp.topology.mapping.SchemaType", SCHEMA_TYPE),
                source("com.example.amqp.topology.mapping.Mappings", MAPPINGS),
                source("com.example.amqp.topology.mapping.TypeMapping", TYPE_MAPPING),
                source("org.springframework.context.annotation.Configuration", CONFIGURATION),
                source("org.springframework.context.annotation.Bean", BEAN),
                source("org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
                        CONDITIONAL_ON_MISSING_BEAN)));
    }

    /**
     * Runs the processor over the {@link #stubs()} plus the given fixtures with an in-JVM
     * {@code javac} in {@code -proc:only} mode. The emitted {@code @Configuration} is captured in
     * memory (and, on the happy path, attributed against the stand-ins — proving it compiles).
     */
    private static Result compile(JavaFileObject... fixtures) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager std =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        try {
            // Full isolation: every referenced type is an in-memory source, so no user classpath.
            std.setLocation(StandardLocation.CLASS_PATH, List.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<JavaFileObject> sources = stubs();
        sources.addAll(List.of(fixtures));
        CapturingFileManager fileManager = new CapturingFileManager(std);
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, List.of("-proc:only"), null, sources);
        task.setProcessors(List.of(new EventMappingProcessor()));
        boolean ok = task.call();

        Map<String, String> generated = new HashMap<>();
        fileManager.generated.forEach((name, output) -> generated.put(name, output.content()));
        return new Result(ok, diagnostics.getDiagnostics(), generated);
    }

    private static JavaFileObject source(String fqcn, String code) {
        return new StringSource(fqcn, code);
    }

    private record Result(
            boolean ok,
            List<Diagnostic<? extends JavaFileObject>> diagnostics,
            Map<String, String> generated) {

        List<Diagnostic<? extends JavaFileObject>> errors() {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .toList();
        }

        List<String> errorMessages() {
            return errors().stream().map(d -> d.getMessage(Locale.ROOT)).toList();
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String fqcn, String code) {
            super(URI.create("string:///" + fqcn.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class InMemoryOutput extends SimpleJavaFileObject {
        private final StringWriter writer = new StringWriter();

        InMemoryOutput(String fqcn) {
            super(URI.create("mem:///" + fqcn.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
        }

        @Override
        public Writer openWriter() {
            return writer;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            // javac reads generated sources back in the next processing round; serve what was written.
            return writer.toString();
        }

        String content() {
            return writer.toString();
        }
    }

    private static final class CapturingFileManager
            extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, InMemoryOutput> generated = new HashMap<>();

        CapturingFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                Location location, String className, JavaFileObject.Kind kind, FileObject sibling)
                throws IOException {
            if (location == StandardLocation.SOURCE_OUTPUT && kind == JavaFileObject.Kind.SOURCE) {
                InMemoryOutput output = new InMemoryOutput(className);
                generated.put(className, output);
                return output;
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }
    }
}
