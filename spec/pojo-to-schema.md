# Specification: Event Contract Development and Schema Publication Workflow

## Purpose

Define a standardized workflow for creating, validating, and publishing event contracts that balances developer productivity with enterprise governance.

## Guiding Principles

1. Developers author events using language-native models (e.g., Java Records/POJOs).
2. JSON Schema (or Avro/Protobuf) is the published contract, not the implementation.
3. Schemas are generated automatically, never manually maintained.
4. The CI/CD pipeline is the only component authorized to publish contracts.
5. Compatibility validation is enforced before any schema is published.

---

# Development Workflow

## Step 1 – Define Event Model

Developers create event models using Java Records (preferred) or POJOs.

Example:

```java
public record CustomerCreatedEvent(
    UUID customerId,
    @Email String email,
    Instant createdAt
) {}
```

Validation annotations should be used whenever possible to enrich the generated schema.

Examples include:

* `@NotNull`
* `@Size`
* `@Pattern`
* `@Email`
* Jackson serialization annotations
* Custom schema annotations (where applicable)

---

## Step 2 – Local Schema Generation

During the local Maven or Gradle build, the schema generation plugin automatically generates the corresponding JSON Schema.

Generation should occur as part of the build lifecycle (e.g., `generate-sources`).

The generated schema is written into the repository (for example):

```
contracts/
    customer-created-event.json
```

Developers are **not expected to manually edit generated schemas**.

---

## Step 3 – Local Validation

Developers review generated schema changes before committing.

Typical workflow:

```
Edit Java Record
        │
Build Project
        │
Generate Schema
        │
Review Git Diff
        │
Commit
```

This provides immediate feedback whenever a contract changes.

---

## Step 4 – Commit

The following artifacts are committed together:

```
Java Record / POJO

Generated JSON Schema

(Optional)
AsyncAPI fragments
```

The repository always contains the latest generated schema.

---

# CI/CD Workflow

The CI pipeline performs the following activities.

```
Checkout Source
        │
Compile
        │
Generate Schema
        │
Compare with Repository
        │
Compatibility Validation
        │
Publish Schema
        │
Generate Documentation
```

---

## Step 1 – Regenerate Schema

The pipeline regenerates every schema from source code using the approved generator version.

This guarantees deterministic output regardless of developer environments.

---

## Step 2 – Verify Schema Drift

The regenerated schema must exactly match the committed schema.

If differences exist:

```
Build Failed

Generated schema is not up-to-date.
Please regenerate and commit the latest schema.
```

This prevents stale or inconsistent contracts from entering the repository.

---

## Step 3 – Compatibility Validation

Before publication, the generated schema is validated against the latest published version.

Compatibility rules may include:

* Backward compatibility
* Forward compatibility
* Full compatibility (where required)

Any compatibility violation causes the pipeline to fail.

---

## Step 4 – Publish Schema

Only the CI/CD pipeline is permitted to publish schemas to the enterprise schema registry.

Developer workstations must never publish schemas directly.

```
CI Pipeline
      │
Publish
      ▼
Apicurio Schema Registry
```

This guarantees:

* trusted publication
* traceability
* reproducibility
* controlled versioning

---

## Step 5 – Documentation Generation

Once publication succeeds, documentation is generated automatically.

Examples include:

* AsyncAPI documentation
* EventCatalog pages
* Architecture Portal updates

Documentation is generated from the published schema to ensure consistency.

---

# Repository Structure (Example)

```
events/
    customer-created/

        CustomerCreatedEvent.java

        customer-created-event.schema.json

        asyncapi.yaml
```

---

# Responsibilities

| Role           | Responsibility                                   |
| -------------- | ------------------------------------------------ |
| Developer      | Implement event models using Java Records/POJOs  |
| Build Plugin   | Generate JSON Schema from source code            |
| Developer      | Review generated schema changes                  |
| Git Repository | Store source code and generated schemas          |
| CI/CD          | Regenerate schemas and verify no drift           |
| CI/CD          | Perform compatibility validation                 |
| CI/CD          | Publish schemas to Apicurio                      |
| CI/CD          | Generate AsyncAPI and EventCatalog documentation |

---

# Governance Rules

The following rules are mandatory:

1. Developers SHALL NOT manually edit generated JSON Schemas.
2. Developers SHALL NOT publish schemas directly to the schema registry.
3. Every schema SHALL be generated from source code.
4. Every published schema SHALL pass compatibility validation.
5. Only the CI/CD pipeline SHALL publish schemas.
6. Documentation SHALL be generated from the published schema.
7. The repository SHALL contain both the source model and the generated schema.
8. Schema generation tooling SHALL use a pinned version to ensure deterministic output across all environments.

---

# Recommended End-to-End Flow

```
                Developer
                    │
          Create Java Record / POJO
                    │
           Local Build (Maven/Gradle)
                    │
         Generate JSON Schema
                    │
          Review Generated Changes
                    │
                 Commit
                    │
                    ▼
              Git Repository
                    │
                    ▼
               CI/CD Pipeline
                    │
          Regenerate JSON Schema
                    │
          Verify No Schema Drift
                    │
      Compatibility Validation
                    │
          Publish to Apicurio
                    │
        Generate AsyncAPI Docs
                    │
       Generate EventCatalog Pages
                    │
                    ▼
        Runtime & Architecture Portal
```

## Benefits

This approach provides:

* Excellent developer experience by allowing developers to work exclusively with language-native models.
* A single, language-neutral contract for cross-team and cross-platform integration.
* Deterministic and reproducible schema generation.
* Automated governance through compatibility validation.
* Controlled publication via CI/CD.
* Documentation that is always synchronized with the published contract.
