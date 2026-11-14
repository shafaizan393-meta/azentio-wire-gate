# AI usage

## Tool used

Copilot SDK in VS Code was used in this development session to scaffold the
Task 1 Java/Spring Boot structure, implementation, tests, and documentation.

## Prompts and work

- Asked the assistant to review the assignment PDF and confirm the required stack.
- Asked it to begin coding while local Python and Docker installation was in
  progress, with a standard project structure.
- Asked it to implement the initial wire API, validation, funds and risk checks,
  PostgreSQL persistence, migrations, and tests.
- Continued with the Python held-wire summary service, offline fake provider,
  optional local Ollama adapter, and focused tests.

## Review notes

- The first cursor query draft used an internal receive-time column. Comparing it
  with the assignment's required `(created_at, id)` keyset pagination revealed the
  mismatch; the query and migration index were corrected.
- A first risk-rule test fixture did not represent the intended corridor. The
  BIC-derived country and corridor were checked against the assignment and the
  fixture was corrected.
- Maven was not preinstalled, so a Maven Wrapper was added. The Java unit,
  controller, and embedded-Kafka tests passed in an earlier run; additional API
  edge-case and PostgreSQL-backed Kafka-redelivery tests were added afterward and
  still need a fresh run. PostgreSQL-backed behavior needs Docker.
- Python 3.14 and the project test extras were installed. The five summary tests
  passed; a later Decimal-parsing and schema-tightening change should be rerun
  before submission.

AI-generated code must be reviewed and understood by the candidate before it is
submitted.
