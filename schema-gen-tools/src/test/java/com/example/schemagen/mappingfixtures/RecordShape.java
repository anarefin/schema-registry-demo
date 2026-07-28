package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.GenerateSchema;

/** Unsupported shape: a record (events must be immutable classes). */
@GenerateSchema
public record RecordShape(String id) {}
