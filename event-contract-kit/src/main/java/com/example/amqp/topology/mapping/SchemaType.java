package com.example.amqp.topology.mapping;

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
