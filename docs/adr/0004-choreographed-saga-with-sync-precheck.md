# 0004 · Choreographed saga with a synchronous gRPC pre-check

* **Status:** Accepted
* **Date:** 2026-11-01

## Context
Placing an order must reserve stock in another service with another database. Users want fast
feedback when an item is out of stock, but ordering must stay available when inventory is degraded.

## Options considered
1. **Synchronous reservation only** (gRPC `ReserveStock` inside the request): immediate answer, but
   order availability = inventory availability, and a timeout leaves the outcome unknown.
2. **Asynchronous saga only:** highly available, but the user learns about missing stock later.
3. **Orchestrated saga** (central state machine / workflow engine): explicit flow, more infrastructure;
   better once the flow has many steps.
4. **Choreographed saga + synchronous, read-only pre-check.**

## Decision
Option 4. Before accepting, order-service calls `CheckAvailability` over gRPC (800 ms deadline, circuit
breaker). `Unavailable` → reject immediately (422). `Unknown` (timeout/open breaker) → accept as
PENDING and count `orders.placed.degraded`. The **authoritative** reservation happens asynchronously:
`OrderPlaced` → `StockReserved` / `StockRejected` → order CONFIRMED / CANCELLED; cancellations emit
`OrderCancelled` → `StockReleased` (compensation).

## Consequences
* ✅ Fast feedback in the common case, availability in the degraded case, no overselling.
* ⚠️ The pre-check can race (stock can sell out between check and reservation), so the UI must handle a
  later rejection of a PENDING order.
* ⚠️ The flow is spread across services; tracing and the order status history are essential for debugging.
* 🔁 Revisit (orchestration) when payment, shipping or loyalty steps are added.
