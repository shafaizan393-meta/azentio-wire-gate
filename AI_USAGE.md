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

## Review notes

- The first cursor query draft used an internal receive-time column. Comparing it
  with the assignment's required `(created_at, id)` keyset pagination revealed the
  mismatch; the query and migration index were corrected.
- A first risk-rule test fixture did not represent the intended corridor. The
  BIC-derived country and corridor were checked against the assignment and the
  fixture was corrected.
- Maven was not preinstalled. A Maven Wrapper was generated and used to run the
  current unit tests successfully (11 tests, no failures). Database-backed service
  and concurrency behavior remains untested because PostgreSQL/Docker was not yet
  available.

AI-generated code must be reviewed and understood by the candidate before it is
submitted.
