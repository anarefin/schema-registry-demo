package com.example.schemagen;

import java.beans.Introspector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Build-only annotation processor that reads {@code @EventMapping} during a contracts module's own
 * {@code compile} and emits two generated sources per module:
 *
 * <ol>
 *   <li>{@code GeneratedEventTypeMappings} — one {@code @AutoConfiguration} of explicit
 *       {@code @Bean TypeMapping} methods, so {@link com.example.amqp.topology.mapping.TypeMapping}
 *       registration is known at build time with zero runtime reflection. Self-activates via the
 *       generated {@code AutoConfiguration.imports} resource.
 *   <li>{@code <beanPrefix>PublisherTopology} — a plain {@code @Configuration} (never
 *       {@code @AutoConfiguration}) declaring the module's domain exchanges (main/DLX/retry) via
 *       {@code DomainTopology}. Deliberately absent from {@code AutoConfiguration.imports}: only the
 *       domain's single publisher should {@code @Import} it.
 * </ol>
 *
 * <p>Keeps no compile dependency on {@code event-contract-kit}: the annotation is matched by FQCN
 * (mirroring {@link GenerateSchemaScanner}) and its attribute values are read through
 * {@link AnnotationMirror}, never a typed annotation instance. Both generated sources reference the
 * kit's {@code Mappings}/{@code TypeMapping}/{@code DomainTopology}/{@code DomainExchanges} and
 * Spring's annotation/bean types by name only — those types resolve on the <em>contracts</em>
 * module's classpath, never here.
 *
 * <p>Output is byte-stable: methods/beans are sorted by the event type's FQCN, and the generated
 * content carries no timestamp. Each {@code TypeMapping} method is
 * {@code @Bean @ConditionalOnMissingBean(name = "<decapitalizedSimpleName>Mapping")} returning a
 * {@code TypeMapping} built only via
 * {@code Mappings.forDomain(group, exchange).json(type, artifactId, routingKey)}.
 *
 * <p>The build fails ({@link Diagnostic.Kind#ERROR}) on a blank {@code groupId}/{@code exchange}/
 * {@code routingKey}, a {@code schemaType} other than {@code JSON}, a within-module duplicate
 * {@code (groupId, artifactId)} or bean name, a module mixing more than one effective
 * {@code groupId} or {@code exchange} (a contracts module represents exactly one domain), or a
 * {@code groupId}/event-package final segment that cannot be used to derive a Java identifier. On
 * any such error nothing is emitted — neither generated source, nor the imports resource — the
 * whole module's generation is all-or-nothing.
 */
@SupportedAnnotationTypes(EventMappingProcessor.EVENT_MAPPING_ANNOTATION)
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class EventMappingProcessor extends AbstractProcessor {

    /** FQCN of {@code com.example.amqp.topology.mapping.EventMapping} — keep in sync. */
    static final String EVENT_MAPPING_ANNOTATION =
            "com.example.amqp.topology.mapping.EventMapping";

    /** Simple name of the emitted {@code @AutoConfiguration} class. */
    static final String GENERATED_SIMPLE_NAME = "GeneratedEventTypeMappings";

    /** Suffix appended to the derived class name of the generated publisher-topology class. */
    static final String PUBLISHER_TOPOLOGY_SUFFIX = "PublisherTopology";

    /** Spring Boot 3+ auto-configuration entry listing (relative to CLASS_OUTPUT). */
    static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private Elements elements;
    private Filer filer;
    private Messager messager;

    private final List<TypeElement> annotatedTypes = new ArrayList<>();
    private boolean written;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment env) {
        super.init(env);
        this.elements = env.getElementUtils();
        this.filer = env.getFiler();
        this.messager = env.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            for (Element annotated : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (annotated instanceof TypeElement type) {
                    annotatedTypes.add(type);
                }
            }
        }
        // Every @EventMapping type is a hand-written source, so all are present in the first round.
        // Emit then (not in the processingOver round) to avoid javac's "file created in the last
        // round will not be subject to annotation processing" warning.
        if (!written && !annotatedTypes.isEmpty()) {
            generate();
            written = true;
        }
        return true;
    }

    private void generate() {
        if (annotatedTypes.isEmpty()) {
            return;
        }

        List<MappingDescriptor> descriptors = perEventValidation();
        if (descriptors == null) {
            return; // never emit partial or invalid output
        }
        if (descriptors.isEmpty()) {
            return; // every annotated type was unexpectedly unmatched by eventMappingMirror(...)
        }

        DomainDescriptor domain = domainValidation(descriptors);
        if (domain == null) {
            return; // never emit partial or invalid output
        }

        descriptors.sort(Comparator.comparing(MappingDescriptor::typeFqcn));
        String eventPackage = commonEventPackage(annotatedTypes);
        String topologyPackage = eventPackage + ".topology";

        String mappingFqcn = topologyPackage + "." + GENERATED_SIMPLE_NAME;
        String topologyFqcn = topologyPackage + "." + domain.publisherTopologySimpleName();

        writeSource(mappingFqcn, renderMappings(topologyPackage, descriptors));
        writeSource(topologyFqcn, renderPublisherTopology(topologyPackage, domain));
        writeImports(mappingFqcn);
    }

    /**
     * Validates every {@code @EventMapping} type in isolation (blank attributes, non-JSON
     * {@code schemaType}, duplicate coordinates/bean names) and returns the resulting descriptors,
     * or {@code null} if any type failed — in which case diagnostics have already been emitted and
     * nothing should be written.
     */
    private List<MappingDescriptor> perEventValidation() {
        List<MappingDescriptor> descriptors = new ArrayList<>();
        Map<String, Element> seenCoordinates = new HashMap<>();
        Map<String, Element> seenBeanNames = new HashMap<>();
        boolean failed = false;

        for (TypeElement type : annotatedTypes) {
            AnnotationMirror mirror = eventMappingMirror(type);
            if (mirror == null) {
                continue; // matched by getElementsAnnotatedWith, so this should not happen
            }
            Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                    elements.getElementValuesWithDefaults(mirror);

            String groupId = string(values, "groupId");
            String exchange = string(values, "exchange");
            String routingKey = string(values, "routingKey");
            String artifactIdRaw = string(values, "artifactId");
            String schemaType = enumConstant(values, "schemaType");

            boolean typeFailed = false;
            if (isBlank(groupId)) {
                error("@EventMapping.groupId must be non-blank", type);
                typeFailed = true;
            }
            if (isBlank(exchange)) {
                error("@EventMapping.exchange must be non-blank", type);
                typeFailed = true;
            }
            if (isBlank(routingKey)) {
                error("@EventMapping.routingKey must be non-blank", type);
                typeFailed = true;
            }
            if (!"JSON".equals(schemaType)) {
                error("@EventMapping.schemaType must be JSON (only JSON is supported by generated "
                        + "TypeMapping beans), but was " + schemaType, type);
                typeFailed = true;
            }
            if (typeFailed) {
                failed = true;
                continue;
            }

            String simpleName = type.getSimpleName().toString();
            String artifactId = isBlank(artifactIdRaw) ? simpleName : artifactIdRaw;
            String beanName = Introspector.decapitalize(simpleName) + "Mapping";

            Element coordinateOwner = seenCoordinates.putIfAbsent(groupId + "/" + artifactId, type);
            if (coordinateOwner != null) {
                error("Duplicate effective schema coordinates (" + groupId + ", " + artifactId
                        + ") — also on " + coordinateOwner.getSimpleName(), type);
                failed = true;
                continue;
            }
            Element beanOwner = seenBeanNames.putIfAbsent(beanName, type);
            if (beanOwner != null) {
                error("Duplicate mapping bean name '" + beanName + "' — also on "
                        + beanOwner.getSimpleName(), type);
                failed = true;
                continue;
            }

            descriptors.add(new MappingDescriptor(
                    type, type.getQualifiedName().toString(), beanName, groupId, exchange,
                    artifactId, routingKey));
        }

        return failed ? null : descriptors;
    }

    /**
     * Validates the module represents exactly one domain — a single effective {@code groupId} and
     * {@code exchange} shared by every event — and that both the {@code groupId}'s and the shared
     * event package's final segment can be turned into a legal Java identifier. Returns the
     * resulting {@link DomainDescriptor}, or {@code null} if invalid (diagnostics already emitted).
     */
    private DomainDescriptor domainValidation(List<MappingDescriptor> descriptors) {
        boolean failed = false;

        MappingDescriptor first = descriptors.get(0);
        for (MappingDescriptor d : descriptors) {
            if (!d.groupId().equals(first.groupId())) {
                error("Mixed @EventMapping.groupId within one contracts module: '" + first.groupId()
                        + "' (" + first.typeFqcn() + ") vs '" + d.groupId() + "' (" + d.typeFqcn()
                        + ") — a contracts module represents exactly one domain and must share a "
                        + "single groupId", d.element());
                failed = true;
            }
            if (!d.exchange().equals(first.exchange())) {
                error("Mixed @EventMapping.exchange within one contracts module: '"
                        + first.exchange() + "' (" + first.typeFqcn() + ") vs '" + d.exchange()
                        + "' (" + d.typeFqcn() + ") — a contracts module represents exactly one "
                        + "domain and must share a single exchange", d.element());
                failed = true;
            }
        }
        if (failed) {
            return null;
        }

        String groupId = first.groupId();
        String exchange = first.exchange();
        String beanPrefix = lastSegment(groupId);
        if (!SourceVersion.isIdentifier(beanPrefix)) {
            error("@EventMapping.groupId '" + groupId + "' has a final segment '" + beanPrefix
                    + "' that is not a valid Java identifier — needed to derive the generated "
                    + "PublisherTopology bean names (<segment>Exchange/Dlx/RetryExchange)",
                    first.element());
            failed = true;
        }

        String eventPackage = commonEventPackage(annotatedTypes);
        String eventPackageSegment = lastSegment(eventPackage);
        if (eventPackage.isEmpty() || !SourceVersion.isIdentifier(eventPackageSegment)) {
            error("@EventMapping event types in this module share no common package with a valid "
                    + "final segment ('" + eventPackage + "') — needed to derive the generated "
                    + "PublisherTopology class name", first.element());
            failed = true;
        }

        if (failed) {
            return null;
        }

        String publisherTopologySimpleName = capitalize(eventPackageSegment) + PUBLISHER_TOPOLOGY_SUFFIX;
        return new DomainDescriptor(groupId, exchange, beanPrefix, publisherTopologySimpleName);
    }

    private void writeSource(String fqcn, String source) {
        try {
            JavaFileObject file =
                    filer.createSourceFile(fqcn, annotatedTypes.toArray(new Element[0]));
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write generated source " + fqcn, e);
        }
    }

    private void writeImports(String fqcn) {
        try {
            FileObject resource = filer.createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    AUTO_CONFIGURATION_IMPORTS,
                    annotatedTypes.toArray(new Element[0]));
            try (Writer writer = resource.openWriter()) {
                writer.write(fqcn);
                writer.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to write " + AUTO_CONFIGURATION_IMPORTS, e);
        }
    }

    // ---- @EventMapping mirror reading (FQCN-matched, no compile dep on the kit) ----

    private AnnotationMirror eventMappingMirror(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType = (TypeElement) mirror.getAnnotationType().asElement();
            if (annotationType.getQualifiedName().contentEquals(EVENT_MAPPING_ANNOTATION)) {
                return mirror;
            }
        }
        return null;
    }

    private static String string(
            Map<? extends ExecutableElement, ? extends AnnotationValue> values, String attribute) {
        Object value = raw(values, attribute);
        return value == null ? null : value.toString();
    }

    private static String enumConstant(
            Map<? extends ExecutableElement, ? extends AnnotationValue> values, String attribute) {
        Object value = raw(values, attribute);
        if (value instanceof VariableElement constant) {
            return constant.getSimpleName().toString();
        }
        return value == null ? null : value.toString();
    }

    private static Object raw(
            Map<? extends ExecutableElement, ? extends AnnotationValue> values, String attribute) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : values.entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(attribute)) {
                return entry.getValue().getValue();
            }
        }
        return null;
    }

    // ---- package derivation ----

    /**
     * The package shared by every {@code @EventMapping} record in the module (all contract events
     * sit in one package). Falls back to the longest common package prefix if they ever diverge —
     * an empty result (no common prefix at all) is caught by {@link #domainValidation}, which is
     * always run before this value is used to generate anything.
     */
    private String commonEventPackage(List<TypeElement> types) {
        List<String> packages = new ArrayList<>();
        for (TypeElement type : types) {
            packages.add(elements.getPackageOf(type).getQualifiedName().toString());
        }
        return longestCommonPackage(packages);
    }

    private static String longestCommonPackage(List<String> packages) {
        String[] prefix = packages.get(0).split("\\.");
        int matched = prefix.length;
        for (String pkg : packages) {
            String[] segments = pkg.split("\\.");
            int limit = Math.min(matched, segments.length);
            int i = 0;
            while (i < limit && prefix[i].equals(segments[i])) {
                i++;
            }
            matched = i;
        }
        return String.join(".", List.of(prefix).subList(0, matched));
    }

    private static String lastSegment(String dotted) {
        int lastDot = dotted.lastIndexOf('.');
        return lastDot < 0 ? dotted : dotted.substring(lastDot + 1);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- source rendering (byte-stable) ----

    static String renderMappings(String packageName, List<MappingDescriptor> descriptors) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.example.amqp.topology.mapping.Mappings;\n");
        sb.append("import com.example.amqp.topology.mapping.TypeMapping;\n");
        sb.append("import org.springframework.boot.autoconfigure.AutoConfiguration;\n");
        sb.append("import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n\n");
        sb.append("/**\n");
        sb.append(" * Generated by schema-gen-tools {@code EventMappingProcessor} from the module's\n");
        sb.append(" * {@code @EventMapping} records. Do not edit — regenerated on every compile.\n");
        sb.append(" * Self-activates via the generated {@code AutoConfiguration.imports} resource.\n");
        sb.append(" */\n");
        sb.append("@AutoConfiguration\n");
        sb.append("public class ").append(GENERATED_SIMPLE_NAME).append(" {\n");
        for (MappingDescriptor d : descriptors) {
            sb.append('\n');
            sb.append("    @Bean\n");
            sb.append("    @ConditionalOnMissingBean(name = \"").append(d.beanName()).append("\")\n");
            sb.append("    public TypeMapping ").append(d.beanName()).append("() {\n");
            sb.append("        return Mappings.forDomain(\"").append(d.groupId()).append("\", \"")
                    .append(d.exchange()).append("\")\n");
            sb.append("                .json(").append(d.typeFqcn()).append(".class, \"")
                    .append(d.artifactId()).append("\", \"").append(d.routingKey()).append("\");\n");
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    static String renderPublisherTopology(String packageName, DomainDescriptor domain) {
        String exchangeBean = domain.beanPrefix() + "Exchange";
        String dlxBean = domain.beanPrefix() + "Dlx";
        String retryExchangeBean = domain.beanPrefix() + "RetryExchange";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.example.amqp.topology.DomainExchanges;\n");
        sb.append("import com.example.amqp.topology.DomainTopology;\n\n");
        sb.append("import org.springframework.amqp.core.TopicExchange;\n");
        sb.append("import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Configuration;\n\n");
        sb.append("/**\n");
        sb.append(" * Generated by schema-gen-tools {@code EventMappingProcessor} from the module's\n");
        sb.append(" * {@code @EventMapping} records. Do not edit — regenerated on every compile.\n");
        sb.append(" *\n");
        sb.append(" * <p>Opt-in, not auto-configured: declares this domain's three AMQP exchanges\n");
        sb.append(" * (main/DLX/retry) but is deliberately absent from\n");
        sb.append(" * {@code AutoConfiguration.imports} — only the domain's single publisher should\n");
        sb.append(" * {@code @Import} this class.\n");
        sb.append(" */\n");
        sb.append("@Configuration\n");
        sb.append("public class ").append(domain.publisherTopologySimpleName()).append(" {\n\n");
        sb.append("    private static final DomainExchanges EX = DomainTopology.of(\"")
                .append(domain.exchange()).append("\");\n");

        appendExchangeBean(sb, exchangeBean, "main");
        appendExchangeBean(sb, dlxBean, "dlx");
        appendExchangeBean(sb, retryExchangeBean, "retry");

        sb.append("}\n");
        return sb.toString();
    }

    private static void appendExchangeBean(StringBuilder sb, String beanName, String accessor) {
        sb.append('\n');
        sb.append("    @Bean\n");
        sb.append("    @ConditionalOnMissingBean(name = \"").append(beanName).append("\")\n");
        sb.append("    public TopicExchange ").append(beanName).append("() {\n");
        sb.append("        return EX.").append(accessor).append("();\n");
        sb.append("    }\n");
    }

    private void error(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Validated, effective coordinates for one generated {@code @Bean TypeMapping} method. */
    record MappingDescriptor(
            Element element,
            String typeFqcn,
            String beanName,
            String groupId,
            String exchange,
            String artifactId,
            String routingKey) {}

    /**
     * Validated, effective per-module domain coordinates for the generated publisher-topology
     * class: {@code beanPrefix} is the final {@code groupId} segment (e.g. {@code events.customers}
     * → {@code customers}); {@code publisherTopologySimpleName} is
     * {@code capitalize(lastEventPackageSegment) + "PublisherTopology"} (no singularization).
     */
    record DomainDescriptor(
            String groupId,
            String exchange,
            String beanPrefix,
            String publisherTopologySimpleName) {}
}
