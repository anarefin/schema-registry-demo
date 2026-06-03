# Graph Report - .  (2026-06-02)

## Corpus Check
- Corpus is ~30,464 words - fits in a single context window. You may not need a graph.

## Summary
- 547 nodes · 765 edges · 45 communities (23 shown, 22 thin omitted)
- Extraction: 77% EXTRACTED · 23% INFERRED · 0% AMBIGUOUS · INFERRED: 173 edges (avg confidence: 0.81)
- Token cost: 25,600 input · 5,300 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Schema Messaging Core|Schema Messaging Core]]
- [[_COMMUNITY_AMQP Auto-Configuration|AMQP Auto-Configuration]]
- [[_COMMUNITY_DLX Failure Recovery|DLX Failure Recovery]]
- [[_COMMUNITY_Schema Version Pinning Tests|Schema Version Pinning Tests]]
- [[_COMMUNITY_Architecture Principles|Architecture Principles]]
- [[_COMMUNITY_Consumer Order Processing|Consumer Order Processing]]
- [[_COMMUNITY_Health & Startup Validation|Health & Startup Validation]]
- [[_COMMUNITY_Queue Topology Configuration|Queue Topology Configuration]]
- [[_COMMUNITY_Breaking Change Demo Schema|Breaking Change Demo Schema]]
- [[_COMMUNITY_Customer Schema v2|Customer Schema v2]]
- [[_COMMUNITY_Message Conversion Pipeline|Message Conversion Pipeline]]
- [[_COMMUNITY_Customer Schema v1 Baseline|Customer Schema v1 Baseline]]
- [[_COMMUNITY_Exception Taxonomy|Exception Taxonomy]]
- [[_COMMUNITY_Customer Event Integration Tests|Customer Event Integration Tests]]
- [[_COMMUNITY_Failure Routing Concepts|Failure Routing Concepts]]
- [[_COMMUNITY_Routing Decision Logic|Routing Decision Logic]]
- [[_COMMUNITY_Contract Type Mapping Config|Contract Type Mapping Config]]
- [[_COMMUNITY_Observability Tests|Observability Tests]]
- [[_COMMUNITY_Customer Schema Evolution Tests|Customer Schema Evolution Tests]]
- [[_COMMUNITY_Customer JSON Serialization Tests|Customer JSON Serialization Tests]]
- [[_COMMUNITY_Order Schema Evolution Tests|Order Schema Evolution Tests]]
- [[_COMMUNITY_Consumer Application Entry|Consumer Application Entry]]
- [[_COMMUNITY_Startup Validator Tests|Startup Validator Tests]]
- [[_COMMUNITY_Order Protobuf Round-trip Tests|Order Protobuf Round-trip Tests]]
- [[_COMMUNITY_Producer Application Entry|Producer Application Entry]]
- [[_COMMUNITY_Producer AMQP Config|Producer AMQP Config]]
- [[_COMMUNITY_Serialization Strategy Impls|Serialization Strategy Impls]]
- [[_COMMUNITY_Exception Routing Taxonomy|Exception Routing Taxonomy]]
- [[_COMMUNITY_Customers Package Info|Customers Package Info]]
- [[_COMMUNITY_Order Contracts Package Info|Order Contracts Package Info]]
- [[_COMMUNITY_Core Package Info File|Core Package Info File]]
- [[_COMMUNITY_Producer Application Class|Producer Application Class]]
- [[_COMMUNITY_Idempotency Check Method|Idempotency Check Method]]
- [[_COMMUNITY_TypeMapping Coordinate Lookup|TypeMapping Coordinate Lookup]]
- [[_COMMUNITY_Serialization SPI Serialize|Serialization SPI Serialize]]
- [[_COMMUNITY_Serialization SPI Deserialize|Serialization SPI Deserialize]]
- [[_COMMUNITY_Protobuf Deserialize Method|Protobuf Deserialize Method]]
- [[_COMMUNITY_JSON Schema Deserialize Method|JSON Schema Deserialize Method]]
- [[_COMMUNITY_EventConsumerSupport Test Class|EventConsumerSupport Test Class]]

## God Nodes (most connected - your core abstractions)
1. `AmqpConfiguration` - 29 edges
2. `toString()` - 19 edges
3. `SchemaMessagingAutoConfiguration` - 15 edges
4. `Technical Report: Schema-Governed Messaging` - 15 edges
5. `SchemaResolver` - 14 edges
6. `SchemaResolverTest` - 13 edges
7. `SchemaMessagingMetrics` - 13 edges
8. `DlxRoutingIT` - 12 edges
9. `SchemaMessagingException` - 11 edges
10. `SchemaMessageHeaders` - 10 edges

## Surprising Connections (you probably didn't know these)
- `OrderCreatedTest` --conceptually_related_to--> `Wire Format — Raw Bytes + X-Schema-* Headers`  [EXTRACTED]
  order-contracts/src/test/java/com/example/contracts/orders/OrderCreatedTest.java → CLAUDE.md
- `CustomerRegistered Incompatible Schema (demo)` --conceptually_related_to--> `BACKWARD Compatibility Rule`  [EXTRACTED]
  customer-contracts/src/test/resources/schemas/customer-registered-incompatible.json → docs/TECHNICAL-REPORT.md
- `OrderCreatedEvolutionTest` --conceptually_related_to--> `BACKWARD Compatibility Rule`  [EXTRACTED]
  order-contracts/src/test/java/com/example/contracts/orders/OrderCreatedEvolutionTest.java → docs/TECHNICAL-REPORT.md
- `CustomerContractsConfiguration (producer)` --implements--> `Schema Version Pinning (fail-fast on startup)`  [EXTRACTED]
  producer-service/src/main/java/com/example/producer/config/CustomerContractsConfiguration.java → docs/TECHNICAL-REPORT.md
- `OrderContractsConfiguration (producer)` --implements--> `Schema Version Pinning (fail-fast on startup)`  [EXTRACTED]
  producer-service/src/main/java/com/example/producer/config/OrderContractsConfiguration.java → docs/TECHNICAL-REPORT.md

## Hyperedges (group relationships)
- **DLX Routing Chain — Advice + Recoverer + Topology Config** — amqp_dlxroutingadvice, amqp_dlxmessagerecoverer, config_amqpconfiguration [EXTRACTED 1.00]
- **Consumer Listener Idempotency — Listeners + IdempotencyFilter Pattern** — listener_customereventlistener, listener_ordereventlistener, concept_idempotency_filter [EXTRACTED 1.00]
- **Schema Version Pinning Flow — Config + YML + Pinning Tests** — config_customercontractsconfiguration, config_ordercontractsconfiguration, resources_application_yml, consumer_schemaversionpinningit [EXTRACTED 1.00]
- **Schema Evolution BACKWARD Compatibility Proof (JSON + Proto)** — customer_contracts_customerregisteredevolutiontest, order_contracts_ordercreatedevolutiontest, concept_backward_compatibility [EXTRACTED 1.00]
- **Producer TypeMapping Configuration Flow** — producer_config_customercontractsconfiguration, producer_config_ordercontractsconfiguration, concept_type_mapping_plugin_pattern [EXTRACTED 1.00]
- **Failure Routing DLQ/Retry Topology** — concept_failure_topology, concept_ttl_ladder_retry, concept_dlq_failure_headers [EXTRACTED 1.00]
- **Exception Taxonomy Drives DLQ vs Retry Routing Decision** — consumer_eventconsumersupport_classify, consumer_routingdecision_enum, exception_schemavalidationexception_class, exception_deserializationexception_class, exception_serializationexception_class, exception_incompatibleschematypeexception_class [EXTRACTED 1.00]
- **Schema-Aware Message Produce/Consume Pipeline** — converter_schemaawaremessageconverter_class, mapping_typemappingregistry_class, model_resolvedschema_record, model_schemacoordinates_record, converter_schemamessageheaders_class [EXTRACTED 1.00]
- **Startup Schema Cache Pre-Warm and Health Reporting** — registry_cacheprewarmer_class, health_registryhealthindicator_class, mapping_typemappingregistry_class [EXTRACTED 1.00]
- **Schema Resolution Cache Layer: ApicurioClient + SchemaResolver + ApicurioCacheProperties** — registry_apicurioclient_apicurioclient, registry_schemaresolver_schemaresolver, registry_apicuriocacheproperties_apicuriocacheproperties [EXTRACTED 1.00]
- **Serialization SPI and Implementations: SerializationStrategy + ProtobufStrategy + JsonSchemaStrategy** — serde_serializationstrategy_serializationstrategy, serde_protobufstrategy_protobufstrategy, serde_jsonschemastrategy_jsonschemastrategy [EXTRACTED 1.00]
- **Startup Registry Validation Flow: StartupSchemaValidator + ApicurioClient + TypeMappingRegistry** — registry_startupschemavalidator_startupschemavalidator, registry_apicurioclient_apicurioclient, concept_failfaststartup [EXTRACTED 1.00]

## Communities (45 total, 22 thin omitted)

### Community 0 - "Schema Messaging Core"
Cohesion: 0.07
Nodes (10): Registry Client Facade Pattern, MeterBinder, asStale(), SchemaMessagingMetrics, ApicurioCacheProperties, ApicurioClient, SchemaResolver, Stale-on-Failure Cache Strategy (+2 more)

### Community 1 - "AMQP Auto-Configuration"
Cohesion: 0.05
Nodes (8): DlxRoutingAdvice, SchemaMessagingAutoConfiguration, MessageConverter, MethodInterceptor, JsonSchemaStrategy, ProtobufStrategy, SerializationStrategy, SchemaAwareMessageConverterTest

### Community 2 - "DLX Failure Recovery"
Cohesion: 0.08
Nodes (8): DlxMessageRecoverer, DlxRoutingIT, EventConsumerSupport, EventConsumerSupportTest, OrderController, CustomerEventListener, MessageRecoverer, toString()

### Community 3 - "Schema Version Pinning Tests"
Cohesion: 0.09
Nodes (6): SchemaVersionPinningIT, SchemaAwareMessageConverter, SchemaAwareMessageConverterTest, SchemaMessageHeaders, TypeMappingRegistry, versionExpression()

### Community 4 - "Architecture Principles"
Cohesion: 0.09
Nodes (34): BACKWARD Compatibility Rule, Schema Cache as Resilience Feature, Core Must Not Depend on Contracts (machine-enforced), X-Failure-* Headers on DLQ for Forensics, Failure Topology (DLQ/DLX/Retry Ladder), POC-Grade Idempotency (in-memory Caffeine), Jackson FAIL_ON_UNKNOWN_PROPERTIES=false for BACKWARD Compat, Maven Chosen Over Gradle for POC (Apicurio plugin rationale) (+26 more)

### Community 5 - "Consumer Order Processing"
Cohesion: 0.07
Nodes (8): IdempotencyFilter, OrderCreatedIT, CustomerController, ProducerValidationTest, OrderEventListener, contentType(), fromArtifactType(), EventPublisher

### Community 6 - "Health & Startup Validation"
Cohesion: 0.08
Nodes (14): Fail-Fast Startup (auto-register=OFF), QueueDepthHealthIndicator, RegistryHealthIndicator, HealthIndicator, InitializingBean, ApicurioClient.fetchByCoordinates, ApicurioClient.fetchByGlobalId, ApicurioClient.latestVersion (+6 more)

### Community 8 - "Breaking Change Demo Schema"
Cohesion: 0.07
Nodes (26): description, type, description, type, description, description, type, description (+18 more)

### Community 9 - "Customer Schema v2"
Cohesion: 0.07
Nodes (26): description, type, description, description, type, description, type, description (+18 more)

### Community 10 - "Message Conversion Pipeline"
Cohesion: 0.11
Nodes (24): SchemaMessagingAutoConfiguration, EventConsumerSupport, EventConsumerSupport#populateFailureHeaders, IdempotencyFilter, SchemaAwareMessageConverter, SchemaAwareMessageConverter#toMessage, SchemaMessageHeaders, SchemaMessageHeaders#getRetryCount (+16 more)

### Community 11 - "Customer Schema v1 Baseline"
Cohesion: 0.08
Nodes (23): description, type, description, description, type, description, type, description (+15 more)

### Community 12 - "Exception Taxonomy"
Cohesion: 0.09
Nodes (8): DeserializationException, IncompatibleSchemaTypeException, RegistryUnavailableException, SchemaMessagingException, SchemaNotFoundException, SchemaValidationException, SerializationException, RuntimeException

### Community 13 - "Customer Event Integration Tests"
Cohesion: 0.10
Nodes (6): CustomerRegisteredIT, CustomerContractsConfiguration, OrderContractsConfiguration, latest(), CustomerContractsConfiguration, OrderContractsConfiguration

### Community 14 - "Failure Routing Concepts"
Cohesion: 0.17
Nodes (21): DlxMessageRecoverer — DLQ/Retry Message Recoverer, DlxRoutingAdvice — AOP Intercept for DLQ Routing, BACKWARD Compatibility Governance — CI Merge Gate, DLX/DLQ/Retry Failure Topology — TTL Ladder Retry Pattern, Exception Taxonomy — Permanent vs Transient Routing Decision, IdempotencyFilter — In-Memory X-Message-Id Deduplication, Schema-Governed Messaging — Core Architectural Pattern, Wire Format — Raw Bytes + X-Schema-* Headers (+13 more)

### Community 15 - "Routing Decision Logic"
Cohesion: 0.19
Nodes (15): EventConsumerSupport#classify, RoutingDecision, SchemaAwareMessageConverter#fromMessage, SchemaAwareMessageConverter#resolveSchema, SchemaMessageHeaders#getGlobalId, DeserializationException, IncompatibleSchemaTypeException, RegistryUnavailableException (+7 more)

### Community 16 - "Contract Type Mapping Config"
Cohesion: 0.32
Nodes (8): Schema Version Pinning — Lock to Specific Registered Version, StartupSchemaValidator — Fail-Fast on Missing Pinned Schema, CustomerContractsConfiguration — TypeMapping for CustomerRegistered, OrderContractsConfiguration — TypeMapping for OrderCreated, ConsumerApplication — Spring Boot Entry Point, SchemaVersionPinningIT — Integration Test for Schema Version Pinning, StartupSchemaValidatorIT — Integration Test for auto-register=OFF Startup Abort, consumer-service application.yml — Consumer Configuration

### Community 26 - "Serialization Strategy Impls"
Cohesion: 0.67
Nodes (3): JsonSchemaStrategy.serialize, JsonSchemaStrategy.validate (networknt Draft 2020-12), ProtobufStrategy.serialize

## Knowledge Gaps
- **93 isolated node(s):** `title`, `description`, `type`, `type`, `description` (+88 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **22 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `toString()` connect `DLX Failure Recovery` to `Schema Messaging Core`, `Schema Version Pinning Tests`, `Consumer Order Processing`?**
  _High betweenness centrality (0.066) - this node is a cross-community bridge._
- **Why does `SchemaAwareMessageConverter` connect `Schema Version Pinning Tests` to `AMQP Auto-Configuration`?**
  _High betweenness centrality (0.051) - this node is a cross-community bridge._
- **Are the 17 inferred relationships involving `toString()` (e.g. with `.toMessage_validationFails_throwsAndNoMessageProduced()` and `.getGlobalId()`) actually correct?**
  _`toString()` has 17 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `Technical Report: Schema-Governed Messaging` (e.g. with `Grafana Prometheus Datasource Config` and `Testing Guide`) actually correct?**
  _`Technical Report: Schema-Governed Messaging` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `title`, `description`, `type` to the rest of the system?**
  _95 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Schema Messaging Core` be split into smaller, more focused modules?**
  _Cohesion score 0.07372549019607844 - nodes in this community are weakly interconnected._
- **Should `AMQP Auto-Configuration` be split into smaller, more focused modules?**
  _Cohesion score 0.05226480836236934 - nodes in this community are weakly interconnected._