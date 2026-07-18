package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid record shape marked for schema generation but missing its paired {@code @EventMapping}. */
@GenerateSchema
public record UnpairedRecord(String id) {}
