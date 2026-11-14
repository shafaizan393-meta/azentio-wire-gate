# Project Nostro — Wire Gate

Java 21 / Spring Boot service for making pre-settlement decisions on synthetic
cross-border wires, consuming the same decisions from Kafka, and providing
offline-testable summaries for held wires in Python.

## Architecture

```text
REST controller
  -> Bean Validation
  -> WireDecisionService (@Transactional)
       -> PostgreSQL account lock and UETR idempotency lock
       -> funds / UTC daily-limit checks
       -> configurable risk-rule evaluator
       -> wire_payment, funds_earmark, alert
Kafka wires.inbound (keyed by debtor account)
  -> same WireDecisionService
  -> wires.approved | wires.held | wires.rejected (keyed by UETR)
  -> wires.inbound.DLQ on malformed, invalid, or exhausted transient failures
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

The database and broker must be healthy before startup; Flyway creates the tables
and seeds synthetic accounts on first run. The Java service can be started from
the IDE. Copy `.env.example` to `.env` to configure the Compose PostgreSQL
container; the documented defaults also work without that file.

Kafka producers should key inbound records by debtor `accountId`. Kafka places
records with the same key on the same partition, preserving their order within
that partition. Outcomes are keyed by UETR so consumers can correlate each
decision with its original payment. A database decision is committed before the
outcome is published; if publication fails, Kafka redelivery retrieves the
idempotent stored decision and republishes the same outcome.

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

The load-test migration additionally seeds 200 active INR accounts with IDs
`200000000001` through `200000000200`, each with a large synthetic balance and
daily limit.

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

Embedded Kafka behavior is covered in the regular test suite. PostgreSQL-backed
service and concurrency tests are separate because they require Docker.

## Held-wire summary service

Python 3.10+ is required. Install its dependencies and run the offline tests:

```powershell
py -3 -m pip install -e ".[test]"
py -3 -m pytest
```

With the Java API running, request a summary using the deterministic offline
provider:

```powershell
$env:WIRESUMMARY_PROVIDER = "fake"
py -3 -m uvicorn wiresummary.app:app --port 8000
curl.exe -X POST http://localhost:8000/summaries/f47ac10b-58cc-4372-a567-0e02b2c3d479
```

The CLI alternative is `py -3 -m wiresummary <uetr>`. Set `WIRE_API_URL` if the
Java API is not at `http://localhost:8080`. The response marks
`suggestedActionIsAdvisory` as true; the summary service never changes a payment
decision.

An optional local Ollama provider can be selected with
`WIRESUMMARY_PROVIDER=ollama`, `OLLAMA_URL`, and `OLLAMA_MODEL`. It is not required
for tests and sends only the selected wire response fields to the local provider.

## Kafka load test

The load migration seeds 200 synthetic INR accounts with sufficient balance and
daily limits. Install the optional producer dependency and run the script while
the Java service and Docker Compose broker are running:

```powershell
py -3 -m pip install -e ".[load]"
py -3 scripts/load.py
```

The script sends 10,000 messages by default (1% malformed), tracks outcomes by
UETR and original DLQ offset to ignore duplicate redeliveries, waits up to ten
minutes, and prints throughput and topic counts. Set `LOAD_MESSAGE_COUNT`,
`LOAD_TIMEOUT_SECONDS`, and `KAFKA_BOOTSTRAP_SERVERS` to adjust the run.
Record the actual output here after running with Docker:

```text
Not yet measured: Docker Desktop has not been started in this environment.
```

## PostgreSQL integration tests

The optional Testcontainers profile verifies real PostgreSQL idempotency,
masking/logging, 45-item keyset pagination, and 20-way concurrent earmarking:

```powershell
.\mvnw.cmd verify -Pintegration
```

Docker must be running for this profile. These tests are excluded from plain
`.\mvnw.cmd test`; the normal unit and embedded-Kafka suite does not require Docker.

## Next

Run the PostgreSQL integration suite and 10,000-wire Kafka load benchmark with
Docker, then record the measured throughput and review the implementation before
submission.
