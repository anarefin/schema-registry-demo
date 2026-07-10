package com.example.messaging.core.model;

/** Wire format / schema technology (spec §6). */
public enum SchemaType {

    JSON("application/json");

    private final String contentType;

    SchemaType(String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return contentType;
    }
}
