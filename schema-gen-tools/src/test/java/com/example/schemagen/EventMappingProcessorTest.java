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
 * both the generated source content, the {@code AutoConfiguration.imports} resource, and the
 * {@link Diagnostic.Kind#ERROR} diagnostics.
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

    // Minimal API stand-ins so the emitted @AutoConfiguration actually compiles against the shapes it
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

    private static final String AUTO_CONFIGURATION = """
            package org.springframework.boot.autoconfigure;
            public @interface AutoConfiguration {}
            """;

    private static final String BEAN = """
            package org.springframework.context.annotation;
            public @interface Bean {}
            """;

    private static final String CONDITIONAL_ON_MISSING_BEAN = """
            package org.springframework.boot.autoconfigure.condition;
            public @interface ConditionalOnMissingBean { String[] name() default {}; }
            """;

    private static final String CONFIGURATION = """
            package org.springframework.context.annotation;
            public @interface Configuration {}
            """;

    private static final String TOPIC_EXCHANGE = """
            package org.springframework.amqp.core;
            public final class TopicExchange {
                public TopicExchange(String name, boolean durable, boolean autoDelete) {}
            }
            """;

    private static final String DOMAIN_EXCHANGES = """
            package com.example.amqp.topology;
            import org.springframework.amqp.core.TopicExchange;
            public final class DomainExchanges {
                private final TopicExchange main;
                private final TopicExchange dlx;
                private final TopicExchange retry;
                public DomainExchanges(TopicExchange main, TopicExchange dlx, TopicExchange retry) {
                    this.main = main;
                    this.dlx = dlx;
                    this.retry = retry;
                }
                public TopicExchange getMain() { return main; }
                public TopicExchange getDlx() { return dlx; }
                public TopicExchange getRetry() { return retry; }
            }
            """;

    private static final String DOMAIN_TOPOLOGY = """
            package com.example.amqp.topology;
            import org.springframework.amqp.core.TopicExchange;
            public final class DomainTopology {
                private DomainTopology() {}
                public static DomainExchanges of(String mainExchangeName) {
                    return new DomainExchanges(
                            new TopicExchange(mainExchangeName, true, false),
                            new TopicExchange(mainExchangeName + ".dlx", true, false),
                            new TopicExchange(mainExchangeName + ".retry", true, false));
                }
            }
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
    private static final String TOPOLOGY_FQCN = "demo.events.topology.EventsPublisherTopology";

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
                public final class OrderCreated {
                    private final String id;
                    public OrderCreated(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        String orderShipped = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.demo",
                        exchange = "events.demo.exchange",
                        routingKey = "demo.shipped",
                        artifactId = "OrderShippedV1")
                public final class OrderShipped {
                    private final String id;
                    public OrderShipped(String id) { this.id = id; }
                    public String getId() { return id; }
                }
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
                "import org.springframework.boot.autoconfigure.AutoConfiguration;");
        assertThat(generated).contains(
                "import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;");
        assertThat(generated).contains("import org.springframework.context.annotation.Bean;");
        assertThat(generated).doesNotContain("import org.springframework.context.annotation.Configuration;");
        assertThat(generated).contains("@AutoConfiguration");
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

        assertThat(result.resources)
                .containsEntry(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS, GENERATED_FQCN + "\n");
        // Only the mapping config self-activates — the topology class is never in imports, and no
        // second Spring imports resource is created.
        assertThat(result.resources).containsOnlyKeys(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);

        // groupId "events.demo" -> beanPrefix "demo"; shared package "demo.events" -> "EventsPublisherTopology".
        String topology = result.generated.get(TOPOLOGY_FQCN);
        assertThat(topology).isNotNull();

        assertThat(topology).contains("package demo.events.topology;");
        assertThat(topology).contains("import com.example.amqp.topology.DomainExchanges;");
        assertThat(topology).contains("import com.example.amqp.topology.DomainTopology;");
        assertThat(topology).contains("import org.springframework.amqp.core.TopicExchange;");
        assertThat(topology).contains(
                "import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;");
        assertThat(topology).contains("import org.springframework.context.annotation.Bean;");
        assertThat(topology).contains("import org.springframework.context.annotation.Configuration;");
        assertThat(topology).doesNotContain("import org.springframework.boot.autoconfigure.AutoConfiguration;");
        assertThat(topology).contains("@Configuration");
        assertThat(topology).doesNotContain("@AutoConfiguration");
        assertThat(topology).contains("public class EventsPublisherTopology {");
        assertThat(topology).contains(
                "private static final DomainExchanges EX = DomainTopology.of(\"events.demo.exchange\");");

        assertThat(topology).contains("""
                    @Bean
                    @ConditionalOnMissingBean(name = "demoExchange")
                    public TopicExchange demoExchange() {
                        return EX.getMain();
                    }
                """);
        assertThat(topology).contains("""
                    @Bean
                    @ConditionalOnMissingBean(name = "demoDlx")
                    public TopicExchange demoDlx() {
                        return EX.getDlx();
                    }
                """);
        assertThat(topology).contains("""
                    @Bean
                    @ConditionalOnMissingBean(name = "demoRetryExchange")
                    public TopicExchange demoRetryExchange() {
                        return EX.getRetry();
                    }
                """);
    }

    @Test
    void failsOnBlankGroupId() {
        String source = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "", exchange = "e", routingKey = "r")
                public final class BlankGroup {
                    private final String id;
                    public BlankGroup(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        Result result = compile(source("demo.events.BlankGroup", source));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("groupId").contains("non-blank"));
        assertThat(result.generated).doesNotContainKey(GENERATED_FQCN);
        assertThat(result.resources).doesNotContainKey(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);
    }

    @Test
    void failsOnNonJsonSchemaType() {
        String source = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                import com.example.amqp.topology.mapping.SchemaType;
                @EventMapping(groupId = "g", exchange = "e", routingKey = "r",
                        schemaType = SchemaType.AVRO)
                public final class AvroEvent {
                    private final String id;
                    public AvroEvent(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        Result result = compile(source("demo.events.AvroEvent", source));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("schemaType").contains("JSON").contains("AVRO"));
        assertThat(result.generated).doesNotContainKey(GENERATED_FQCN);
        assertThat(result.resources).doesNotContainKey(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);
    }

    @Test
    void failsOnDuplicateCoordinates() {
        String dupA = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "g", exchange = "e", routingKey = "r1", artifactId = "Same")
                public final class DupA {
                    private final String id;
                    public DupA(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        String dupB = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "g", exchange = "e", routingKey = "r2", artifactId = "Same")
                public final class DupB {
                    private final String id;
                    public DupB(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        Result result = compile(
                source("demo.events.DupA", dupA),
                source("demo.events.DupB", dupB));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("Duplicate effective schema coordinates").contains("Same"));
        assertThat(result.generated).doesNotContainKey(GENERATED_FQCN);
        assertThat(result.resources).doesNotContainKey(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);
    }

    @Test
    void failsOnDuplicateBeanName() {
        // Same simple name in different packages + different groups: coordinates differ, bean name clashes.
        String widgetA = """
                package demo.events.a;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "g.a", exchange = "e", routingKey = "r")
                public final class Widget {
                    private final String id;
                    public Widget(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        String widgetB = """
                package demo.events.b;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "g.b", exchange = "e", routingKey = "r")
                public final class Widget {
                    private final String id;
                    public Widget(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        Result result = compile(
                source("demo.events.a.Widget", widgetA),
                source("demo.events.b.Widget", widgetB));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("Duplicate mapping bean name").contains("widgetMapping"));
        assertThat(result.resources).doesNotContainKey(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);
    }

    @Test
    void failsOnMixedGroupIdWithinModule() {
        // A contracts module represents exactly one domain — every event must share one groupId.
        String eventA = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.demo", exchange = "events.demo.exchange", routingKey = "demo.a")
                public final class EventA {
                    private final String id;
                    public EventA(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        String eventB = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.other", exchange = "events.demo.exchange", routingKey = "demo.b")
                public final class EventB {
                    private final String id;
                    public EventB(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        Result result = compile(
                source("demo.events.EventA", eventA),
                source("demo.events.EventB", eventB));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("Mixed @EventMapping.groupId")
                        .contains("events.demo").contains("events.other")
                        .contains("EventA").contains("EventB"));
        assertThat(result.generated).isEmpty();
        assertThat(result.resources).doesNotContainKey(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);
    }

    @Test
    void failsOnMixedExchangeWithinModule() {
        // A contracts module represents exactly one domain — every event must share one exchange.
        String eventA = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.demo", exchange = "events.demo.exchange", routingKey = "demo.a")
                public final class EventA {
                    private final String id;
                    public EventA(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        String eventB = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.demo", exchange = "events.other.exchange", routingKey = "demo.b")
                public final class EventB {
                    private final String id;
                    public EventB(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        Result result = compile(
                source("demo.events.EventA", eventA),
                source("demo.events.EventB", eventB));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("Mixed @EventMapping.exchange")
                        .contains("events.demo.exchange").contains("events.other.exchange")
                        .contains("EventA").contains("EventB"));
        assertThat(result.generated).isEmpty();
        assertThat(result.resources).doesNotContainKey(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);
    }

    @Test
    void failsOnGroupIdSegmentThatIsNotAValidJavaIdentifier() {
        // Final groupId segment "123bad" starts with a digit, so it cannot become a bean-name prefix.
        String source = """
                package demo.events;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.123bad", exchange = "e", routingKey = "r")
                public final class BadGroupSegment {
                    private final String id;
                    public BadGroupSegment(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        Result result = compile(source("demo.events.BadGroupSegment", source));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("not a valid Java identifier").contains("123bad"));
        assertThat(result.generated).isEmpty();
        assertThat(result.resources).doesNotContainKey(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);
    }

    @Test
    void failsWhenEventTypesShareNoCommonPackage() {
        // "alpha" and "beta" share no package prefix at all, so no publisher-topology class name
        // (and no topology package) can be derived.
        String eventA = """
                package alpha;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.demo", exchange = "events.demo.exchange", routingKey = "demo.a")
                public final class EventA {
                    private final String id;
                    public EventA(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        String eventB = """
                package beta;
                import com.example.amqp.topology.mapping.EventMapping;
                @EventMapping(groupId = "events.demo", exchange = "events.demo.exchange", routingKey = "demo.b")
                public final class EventB {
                    private final String id;
                    public EventB(String id) { this.id = id; }
                    public String getId() { return id; }
                }
                """;
        Result result = compile(
                source("alpha.EventA", eventA),
                source("beta.EventB", eventB));

        assertThat(result.ok).isFalse();
        assertThat(result.errorMessages()).anySatisfy(msg ->
                assertThat(msg).contains("share no common package"));
        assertThat(result.generated).isEmpty();
        assertThat(result.resources).doesNotContainKey(EventMappingProcessor.AUTO_CONFIGURATION_IMPORTS);
    }

    // ---- in-JVM compilation harness ----

    /** The in-memory {@code @EventMapping}/{@code SchemaType} + {@code Mappings}/Spring stand-ins. */
    private static List<JavaFileObject> stubs() {
        return new ArrayList<>(List.of(
                source("com.example.amqp.topology.mapping.EventMapping", EVENT_MAPPING),
                source("com.example.amqp.topology.mapping.SchemaType", SCHEMA_TYPE),
                source("com.example.amqp.topology.mapping.Mappings", MAPPINGS),
                source("com.example.amqp.topology.mapping.TypeMapping", TYPE_MAPPING),
                source("org.springframework.boot.autoconfigure.AutoConfiguration", AUTO_CONFIGURATION),
                source("org.springframework.context.annotation.Bean", BEAN),
                source("org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
                        CONDITIONAL_ON_MISSING_BEAN),
                source("org.springframework.context.annotation.Configuration", CONFIGURATION),
                source("org.springframework.amqp.core.TopicExchange", TOPIC_EXCHANGE),
                source("com.example.amqp.topology.DomainExchanges", DOMAIN_EXCHANGES),
                source("com.example.amqp.topology.DomainTopology", DOMAIN_TOPOLOGY)));
    }

    /**
     * Runs the processor over the {@link #stubs()} plus the given fixtures with an in-JVM
     * {@code javac} in {@code -proc:only} mode. The emitted {@code @AutoConfiguration} and
     * {@code AutoConfiguration.imports} resource are captured in memory.
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
        Map<String, String> resources = new HashMap<>();
        fileManager.resources.forEach((name, output) -> resources.put(name, output.content()));
        return new Result(ok, diagnostics.getDiagnostics(), generated, resources);
    }

    private static JavaFileObject source(String fqcn, String code) {
        return new StringSource(fqcn, code);
    }

    private static final class Result {

        final boolean ok;
        final List<Diagnostic<? extends JavaFileObject>> diagnostics;
        final Map<String, String> generated;
        final Map<String, String> resources;

        Result(
                boolean ok,
                List<Diagnostic<? extends JavaFileObject>> diagnostics,
                Map<String, String> generated,
                Map<String, String> resources) {
            this.ok = ok;
            this.diagnostics = diagnostics;
            this.generated = generated;
            this.resources = resources;
        }

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

    private static final class InMemoryResource extends SimpleJavaFileObject {
        private final StringWriter writer = new StringWriter();

        InMemoryResource(String relativeName) {
            super(URI.create("mem:///" + relativeName), Kind.OTHER);
        }

        @Override
        public Writer openWriter() {
            return writer;
        }

        String content() {
            return writer.toString();
        }
    }

    private static final class CapturingFileManager
            extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, InMemoryOutput> generated = new HashMap<>();
        private final Map<String, InMemoryResource> resources = new HashMap<>();

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

        @Override
        public FileObject getFileForOutput(
                Location location, String packageName, String relativeName, FileObject sibling)
                throws IOException {
            if (location == StandardLocation.CLASS_OUTPUT) {
                String key = packageName == null || packageName.isEmpty()
                        ? relativeName
                        : packageName.replace('.', '/') + '/' + relativeName;
                InMemoryResource resource = new InMemoryResource(key);
                resources.put(key, resource);
                return resource;
            }
            return super.getFileForOutput(location, packageName, relativeName, sibling);
        }
    }
}
