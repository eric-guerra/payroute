# PayRoute

Portfolio reference implementation of a 2023 project, built to show payment orchestration for a retail platform. A single API sits in front of several processors: cards through Stripe, bank debits through ACH files. It does cost and health-aware routing, safe failover, and emits payment events to Kafka.

**Stack:** Java 17, Spring Boot 3.1, Spring Cloud Gateway 2022.0, Resilience4j, Redis (rate limiting), Kafka, Stripe Java SDK (test mode), NACHA ACH files.

## Edge (`gateway/`)
- JWT auth (`SCOPE_payments`). Stripe webhooks are exempt here and verified by signature downstream.
- **Per-merchant rate limiting** (Redis token bucket), keyed on the `merchant_id` claim rather than IP.
- `RequireIdempotencyKey` filter: POSTs without a valid key get a 400.
- Circuit breaker with a fallback that tells clients to retry with the same key.
- Edge retries only for GETs. POSTs are never retried by the gateway; the client's idempotent retry covers them.

## Payments (`payments/`)
- **`ProviderRouter`** filters providers by method, currency, maximum amount and the merchant's allow-list, then orders them:
  1. Healthy before unhealthy. Health is a rolling success rate with a minimum sample count, so an acquirer outage shifts traffic automatically and it shifts back on recovery.
  2. Cheapest fee within each group (fixed fee + basis points).
- **`Orchestrator`:**
  - **Fails over only when safe.** `Unavailable` (no charge was created) moves on to the next provider. `Declined` stops right away: retrying a declined card elsewhere is pointless and breaks card-network rules. A decline also counts as the provider being healthy.
  - **Idempotency per merchant + key.** A replay returns the original payment with no second charge. Reusing a key with a different amount is rejected.
  - **Explicit state machine,** including the ACH reality that a settled debit can still be **RETURNED** days later (R01, R02 and so on).
  - Every transition is recorded and published to Kafka `payments.events`, keyed by merchant so each merchant's events stay ordered.
- **Stripe adapter:**
  - PaymentIntent confirm with `idempotencyKey = pay_<paymentId>`, so a timeout followed by a retry can't double-charge.
  - Card errors map to Declined; rate limits, connection failures and 5xx map to Unavailable.
- **`NachaFile`:** builds a spec-compliant ACH debit file:
  - 94-character records, padded to blocks of 10.
  - PPD/CCD batch, entry hash and control totals.
  - ABA routing checksum validation, and text sanitized to the NACHA character set.

## Test
```bash
mvn test
```
The tests cover routing order and health-based demotion and recovery, failover vs decline, idempotent replay, the ACH return lifecycle, and the NACHA structure (record lengths, entry hash, totals, checksum).
