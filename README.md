# Project Nostro — Wire Gate

Java 21 / Spring Boot REST service for making a pre-settlement decision on synthetic
cross-border wires. The current implementation is the Task 1 foundation: validated
wire intake, funds and daily-limit checks, configurable risk rules, idempotency,
PostgreSQL persistence, and cursor-based lookup.

## Architecture

```text
REST controller
  -> Bean Validation
  -> WireDecisionService (@Transactional)
       -> PostgreSQL account lock and UETR idempotency lock
       -> funds / UTC daily-limit checks
       -> configurable risk-rule evaluator
       -> wire_payment, funds_earmark, alert
```

PostgreSQL is the source of truth. A transaction-scoped advisory lock serializes
requests with the same UETR; a row lock on the debtor account serializes balance
and daily-limit decisions across requests. Schema and synthetic seed data are
managed by Flyway in `src/main/resources/db/migration`.

All account numbers in responses are masked. Do not log full request bodies or
account identifiers.

## Run locally

Requires Java 21 and Docker Compose. A Maven Wrapper is included, so Maven does
not need to be installed separately. Start PostgreSQL and the single-node Kafka
KRaft broker:

```powershell
docker compose up -d
```

Set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` if PostgreSQL is not using the
defaults in `application.yml`, then run:

```powershell
.\mvnw.cmd spring-boot:run
```

The database must be healthy before startup; Flyway creates the tables and seeds
synthetic accounts on first run. The Java service can be started from the IDE;
Kafka is included for the upcoming consumer task.

## API

Create a wire:

```powershell
curl.exe -X POST http://localhost:8080/api/v1/wires `
  -H "Content-Type: application/json" `
  --data-raw '{"msgId":"demo-001","uetr":"f47ac10b-58cc-4372-a567-0e02b2c3d479","createdAt":"2026-11-14T12:00:00Z","amount":25000.00,"currency":"INR","chargeBearer":"SHAR","debtor":{"name":"Synthetic Debtor","accountId":"100000000001"},"debtorAgentBic":"TESTINBBXXX","creditor":{"name":"Synthetic Creditor","accountId":"200000000001","country":"AE"},"creditorAgentBic":"DEMOAEADXXX","purposeCode":"SALA","remittanceInfo":"Synthetic invoice payment"}'
```

Look up a decision:

```powershell
curl.exe http://localhost:8080/api/v1/wires/f47ac10b-58cc-4372-a567-0e02b2c3d479
```

List held wires (newest first; follow `nextCursor` for the next page):

```powershell
curl.exe "http://localhost:8080/api/v1/wires?status=HELD&size=20"
```

Seeded debtor accounts:

| Account ID | Currency | Initial balance | Daily outward limit | Status |
| --- | --- | ---: | ---: | --- |
| `100000000001` | INR | 5,000,000.00 | 2,000,000.00 | ACTIVE |
| `100000000002` | INR | 2,500,000.00 | 1,000,000.00 | ACTIVE |
| `100000000003` | INR | 1,000,000.00 | 500,000.00 | ACTIVE |
| `100000000004` | INR | 150,000.00 | 100,000.00 | ACTIVE |
| `100000000005` | USD | 50,000.00 | 25,000.00 | ACTIVE |
| `100000000006` | INR | 100,000.00 | 50,000.00 | BLOCKED |

Decision checks run in this order: account missing/not active, currency mismatch,
available funds including configured fee, UTC-date daily outward limit, then all
risk rules. Rejected wires do not earmark funds. Approved and held wires earmark
amount plus fee; the daily limit counts wire amounts only.

Fees, high-risk countries, restricted BIC prefixes, and corridor purpose-code
allow-lists are configured in `application.yml` under `wire-gate`.

## Tests

Run unit tests (no database or Docker required):

```powershell
.\mvnw.cmd test
```

If Maven is installed, the equivalent commands are `mvn spring-boot:run` and
`mvn test`.

PostgreSQL-backed service and concurrency tests will be added as a separate
integration task/profile.

## Next

Complete API/persistence edge-case tests, then add Kafka ingestion and dead-letter
handling, the offline-testable Python held-wire summary service, and Docker Compose.
