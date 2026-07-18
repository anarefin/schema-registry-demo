package com.example.amqp.topology.mapping;

/** Wire format / schema technology (spec §6). */
public enum SchemaType {

    JSON("application/json"),

    /** Reserved for a future Avro strategy — not supported by runtime mapping registration yet. */
    AVRO("application/avro");

    private final String contentType;

    SchemaType(String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return contentType;
    }
}
