# 0003 · Database per service: PostgreSQL for orders, MongoDB for inventory

* **Status:** Accepted
* **Date:** 2026-11-01

## Context
Services must deploy and evolve independently. Orders need relational integrity and heavy reporting.
The product catalog has per-category attributes, and stock updates are single-key and very hot.

## Options considered
1. **One shared PostgreSQL schema:** simplest joins, but couples every service's deployments and
   schema changes; a classic distributed-monolith trap.
2. **PostgreSQL per service (schema or instance):** one technology to operate; JSONB covers flexible attributes.
3. **PostgreSQL for orders + MongoDB for inventory.**

## Decision
**Database per service.** order-service owns PostgreSQL (constraints, window functions, outbox in the
same transaction). inventory-service owns MongoDB (documents with flexible attributes, atomic
conditional `findAndModify` per SKU, TTL indexes, multi-document transactions on a replica set).
Option 2 would also be valid; option 3 was chosen partly to **demonstrate SQL vs NoSQL** side by side.

## Consequences
* ✅ No cross-service joins or shared schema; each team chooses its store.
* ⚠️ Cross-service queries need APIs, events or a read model (CQRS); consistency is eventual (saga, ADR 0004).
* ⚠️ Two database technologies to back up, monitor and secure.
