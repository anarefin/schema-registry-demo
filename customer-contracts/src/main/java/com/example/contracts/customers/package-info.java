/**
 * Customer event contracts (code-first). Each event is an annotated Java record — the single source
 * of truth — from which the committed JSON Schema under {@code resources/schemas/} is generated.
 * Also holds the plain-String AMQP routing constants ({@link com.example.contracts.customers.CustomerEventRouting}).
 */
package com.example.contracts.customers;
