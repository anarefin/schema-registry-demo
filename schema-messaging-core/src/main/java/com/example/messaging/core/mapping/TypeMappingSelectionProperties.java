package com.example.messaging.core.mapping;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional filters for which classpath {@link com.example.amqp.topology.mapping.TypeMapping}
 * beans enter the runtime {@link TypeMappingRegistry} / {@link com.example.messaging.core.schema.LocalSchemaCatalog}.
 *
 * <p>{@code events.mappings.include} / {@code events.mappings.exclude} accept Java simple names,
 * FQCNs, or {@code groupId:artifactId} coordinates. Empty include = no include filter (handler
 * scoping still applies when {@code @BitsEventHandler} methods exist).
 */
@ConfigurationProperties(prefix = "events.mappings")
public class TypeMappingSelectionProperties {

    private List<String> include = new ArrayList<>();
    private List<String> exclude = new ArrayList<>();

    public List<String> getInclude() {
        return include;
    }

    public void setInclude(List<String> include) {
        this.include = include != null ? include : new ArrayList<>();
    }

    public List<String> getExclude() {
        return exclude;
    }

    public void setExclude(List<String> exclude) {
        this.exclude = exclude != null ? exclude : new ArrayList<>();
    }
}
