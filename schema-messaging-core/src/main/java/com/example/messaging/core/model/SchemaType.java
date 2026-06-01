package com.example.messaging.core.model;

/** Wire format / schema technology (spec §6). */
public enum SchemaType {

    PROTOBUF("application/x-protobuf"),
    JSON("application/json");

    private final String contentType;

    SchemaType(String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return contentType;
    }

    /** Parse from Apicurio artifactType string (case-insensitive). */
    public static SchemaType fromArtifactType(String artifactType) {
        return switch (artifactType.toUpperCase()) {
            case "PROTOBUF" -> PROTOBUF;
            case "JSON" -> JSON;
            default -> throw new IllegalArgumentException("Unsupported schema type: " + artifactType);
        };
    }
}
