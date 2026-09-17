![build](https://github.com/mrcodertsl/banking/actions/workflows/build.yml/badge.svg)

# Banking

A small Spring Boot REST API for managing bank clients and transferring money between accounts.

## Table of Contents

- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Data Model](#data-model)
- [Database & Migrations](#database--migrations)
- [Configuration](#configuration)
- [Running the App](#running-the-app)
- [API Documentation](#api-documentation)
- [API Reference](#api-reference)
- [Error Handling](#error-handling)
- [Concurrency](#concurrency)
- [Testing](#testing)
- [Continuous Integration](#continuous-integration)
- [Build Tooling](#build-tooling)
- [Known Issues & Limitations](#known-issues--limitations)

## Tech Stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.0 (`spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`) |
| Database | PostgreSQL (via `org.postgresql:postgresql` driver, runtime scope) |
| Schema migrations | Flyway (`spring-boot-starter-flyway` + `flyway-database-postgresql`) |
| Request validation | Jakarta Bean Validation via `spring-boot-starter-validation` (Hibernate Validator 9.1.0.Final) |
| API documentation | springdoc-openapi 3.0.3 (`springdoc-openapi-starter-webmvc-ui`) — Swagger UI + OpenAPI 3.1 |
| Boilerplate reduction | Lombok |
| Build tool | Maven, via the included `./mvnw` / `mvnw.cmd` wrapper (Maven 3.9.16, wrapper 3.3.4 — see `.mvn/wrapper/maven-wrapper.properties`) |
| Test framework | JUnit 5, Mockito 5.14.2, AssertJ, MockMvc / `@WebMvcTest` (`spring-boot-starter-webmvc-test`) |
| Integration testing | Testcontainers 2.0.5 (`spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`) — spins up a real PostgreSQL in Docker for the context test |

## Project Structure

```
src/main/java/com/roladio/banking
├── BankingApplication.java      # @SpringBootApplication entry point
├── config/
│   └── OpenApiConfig.java       # OpenAPI document metadata (title/description/version)
├── controller/
│   └── ClientController.java    # REST endpoints, mapped under /clients
├── dto/                          # all records; most carry Bean Validation constraints
│   ├── ClientRequest.java       # POST /clients + PUT /clients/{id} body
│   ├── ClientResponse.java      # response shape for GET endpoints and POST /clients
│   ├── LastNameRequest.java     # PATCH .../lastName body (no constraints — see API Reference)
│   ├── PhoneNumberRequest.java  # PATCH .../phoneNumber body
│   ├── TransferRequest.java     # POST /clients/transfer body
│   ├── TransactionFilter.java   # optional history search criteria; not bound to any endpoint yet
│   └── TransactionResponse.java # one entry in GET /clients/{id}/transactions
├── exceptions/
│   ├── ClientNotFoundException.java     # unchecked; carries "Client not found: <id>" -> 404
│   ├── InsufficientFundsException.java  # unchecked; carries "Insufficient funds" -> 409
│   ├── ClientHasBalanceException.java   # unchecked; blocks closing a funded account -> 409
│   └── GlobalExceptionHandler.java      # @RestControllerAdvice mapping exceptions -> HTTP status
├── model/
│   ├── Client.java               # JPA entity mapped to the `client` table
│   ├── Transaction.java          # JPA entity for `transaction` — one row written per transfer
│   └── TransactionType.java      # enum, currently just TRANSFER
├── repository/
│   ├── ClientRepository.java     # JpaRepository + row-locking and closed-filtering lookups
│   └── TransactionRepository.java # JpaRepository + per-client history and filtered search queries
└── service/
    └── ClientService.java        # business logic: lookups, updates, transfers, closing, history, search

src/main/resources/
├── application.properties        # base config; datasource via DB_* env vars
├── application-dev.properties    # `dev` profile: SQL logging only
└── db/migration/                 # Flyway migration scripts (see below)

src/test/java/com/roladio/banking
├── BankingApplicationTests.java                  # context test, backed by a Testcontainers PostgreSQL
├── controller/ClientControllerTest.java          # @WebMvcTest slice: HTTP status/body, no DB
└── service/
    ├── ClientServiceTest.java                    # unit tests (Mockito, no DB)
    └── ClientServiceIntegrationTest.java         # @SpringBootTest against a real PostgreSQL
```

All DTOs are Java `record`s (immutable; request DTOs carry Bean Validation constraints — see [Request validation](#request-validation)). All request/response bodies are plain JSON, there is no API versioning or content negotiation beyond the Spring Boot defaults.

`ClientController` is a thin layer: every method just delegates to `ClientService`. The mutating endpoints (`PATCH`/`PUT`/`DELETE`/`POST /transfer`) are `void` and annotated `@ResponseStatus(HttpStatus.NO_CONTENT)`, so they answer `204`; the `GET` endpoints answer `200` with a `ClientResponse`, a list of them, or — for the history route — a list of `TransactionResponse`, and `POST /clients` builds its own `201` response.

`ClientService` maps entities to DTOs through two private helpers, `toResponse(Client)` and `toTransactionResponse(Transaction)`, and persists through **JPA dirty checking** rather than explicit saves: every mutating method is `@Transactional` and loads its entity through the repository (`findByIdAndClosedFalse`, or the locking `findByIdForUpdate` in `transfer` — see [Concurrency](#concurrency)), so the entity is managed and Hibernate flushes the changes at commit. Only `createClient` calls `clientRepository.save(...)`, because a brand-new entity has to be made managed first. This means the absence of a `save(...)` call in `updatePhoneNumber`, `updateLastName`, `updateClient`, and `transfer` is deliberate, not an oversight.

The model is not anemic: `Client` enforces its own invariants through `withdraw`/`deposit`/`close` (see [Data Model](#data-model)), so the service orchestrates but never performs balance arithmetic, funds checks, or closure checks itself.

## Data Model

### `Client` entity (`model/Client.java`)

Lombok-generated `@Getter` at class level, an `@AllArgsConstructor`, and a `protected` no-args constructor (required by JPA, not meant to be called directly). `@Setter` is applied **per field**, deliberately excluding `balance`.

| Field | Java type | Mutable via | Notes |
|---|---|---|---|
| `id` | `Long` | — | `@Id` with `@GeneratedValue(strategy = GenerationType.IDENTITY)` — matches the DB's `GENERATED ALWAYS AS IDENTITY` column. |
| `firstName` | `String` | `@Setter` | |
| `lastName` | `String` | `@Setter` | |
| `balance` | `BigDecimal` | **`withdraw()` / `deposit()` only** | No setter exists. Exact decimal arithmetic matching the `NUMERIC(19,2)` column; comparisons use `compareTo`, arithmetic uses `add`/`subtract`. |
| `phoneNumber` | `String` | `@Setter` | |
| `closed` | `boolean` | **`close()` only** | Soft-delete flag. No setter, and there is no way to reopen a closed client — see [Closing a client](#closing-a-client). |

**`Client` owns the balance invariant.** Rather than exposing a setter and letting callers do the arithmetic, the entity provides two behavior methods:

```java
public void withdraw(BigDecimal amount) {
    if (balance.compareTo(amount) < 0) {
        throw new InsufficientFundsException();
    }
    balance = balance.subtract(amount);
}

public void deposit(BigDecimal amount) {
    balance = balance.add(amount);
}
```

Because there is no `setBalance`, "never go negative" cannot be bypassed from the service layer — `withdraw` is the only path that decreases a balance, and it always checks first. This mirrors the DB's `balance_non_negative` check constraint at the domain level, so the rule is enforced twice and can't drift.

`close()` follows the same pattern for the soft-delete flag — no setter, and the rule lives with the data:

```java
public void close() {
    if (balance.compareTo(BigDecimal.ZERO) != 0) {
        throw new ClientHasBalanceException(id);
    }
    closed = true;
}
```

### Closing a client

`DELETE /clients/{id}` is a **soft delete**: the row is never removed, it is flagged `closed = true`. A client can only be closed once its balance reaches exactly zero, so money can never be stranded in a closed account.

Closure is enforced at the repository level rather than sprinkled through the service — every lookup filters on the flag:

| Repository method | Used by | Effect |
|---|---|---|
| `findAllByClosedFalse()` | `getAllClients` | Closed clients disappear from listings |
| `findByIdAndClosedFalse(id)` | every single-client read and update | Closed clients read as *not found* |
| `findByIdForUpdate(id)` | `transfer` | Query carries `and c.closed = false`, so neither side of a transfer can be a closed account |

The consequence is that a closed client becomes indistinguishable from a non-existent one over HTTP — every route reports `404`. The flag is also one-way: nothing in the API reopens an account.

### `client` table (Postgres, created by `V1__create_client_table.sql`)

| Column | Type | Constraints |
|---|---|---|
| `id` | `BIGINT` | `GENERATED ALWAYS AS IDENTITY PRIMARY KEY` |
| `first_name` | `VARCHAR(100)` | `NOT NULL` |
| `last_name` | `VARCHAR(100)` | nullable |
| `phone_number` | `VARCHAR(20)` | nullable |
| `balance` | `NUMERIC(19,2)` | `NOT NULL DEFAULT 0`, plus `CHECK (balance >= 0)` |
| `closed` | `BOOLEAN` | `NOT NULL DEFAULT FALSE` (added by `V4`) |

`spring.jpa.hibernate.ddl-auto=validate` (see [Configuration](#configuration)) means Hibernate only checks this table matches the `Client` entity at startup — it never creates or alters it. Flyway owns the schema entirely.

### `Transaction` entity (`model/Transaction.java`)

`V5` creates a table to record money movements, and `Transaction` maps to it. Every successful transfer now writes one row — `ClientService.transfer` ends with:

```java
transactionRepository.save(Transaction.transfer(from, to, request.amount()));
```

The save sits inside the same `@Transactional` method as the two balance changes, so the ledger entry and the money movement commit or roll back together: a failed transfer leaves no row behind, and no row *written by the application* can exist without the matching balance change. The 100 rows seeded by `V6` are the exception — they were inserted directly, with no balance changes at all (see [Seed transactions](#seed-transactions-v6)).

| Field | Java type | Column | Notes |
|---|---|---|---|
| `id` | `Long` | `id` | `@GeneratedValue(IDENTITY)` |
| `type` | `TransactionType` | `type` | `@Enumerated(EnumType.STRING)`, so the enum name is stored as text. The enum currently has a single constant, `TRANSFER`. |
| `from` | `Client` | `from_id` | `@ManyToOne(fetch = LAZY)` |
| `to` | `Client` | `to_id` | `@ManyToOne(fetch = LAZY)` |
| `amount` | `BigDecimal` | `amount` | |
| `createdAt` | `Instant` | `created_at` | `insertable = false, updatable = false` — the database's `DEFAULT now()` owns this value |

The entity is read-only from the outside: Lombok generates getters but no setters, and rows are built through a static factory that fixes the type:

```java
public static Transaction transfer(Client from, Client to, BigDecimal amount) {
    return new Transaction(null, TransactionType.TRANSFER, from, to, amount, null);
}
```

Storing `type` as `EnumType.STRING` rather than `ORDINAL` is what makes the column readable and stable — adding or reordering enum constants can't silently reinterpret existing rows.

#### Reading history — `TransactionRepository`

`TransactionRepository` adds one query returning every movement a client took part in, newest first:

```java
@Query("""
        select t from Transaction t
        join fetch t.from
        join fetch t.to
        where t.from.id = :clientId or t.to.id = :clientId
        order by t.createdAt desc
        """)
List<Transaction> findHistoryForClient(@Param("clientId") Long clientId);
```

The two `join fetch` clauses are the point of writing this by hand: `from` and `to` are `FetchType.LAZY`, so rendering a list of transactions would otherwise fire two extra selects per row. Fetching both in the same statement collapses that to a single query.

`ClientService.getClientHistory(id)` calls it, first resolving the client through `findClientById` so an unknown or closed id raises `ClientNotFoundException` (`404`) rather than silently returning an empty list. Results map to `TransactionResponse`, which flattens each party into an id and a display name:

```java
public record TransactionResponse(
        Long id, TransactionType type,
        Long fromId, String fromName,
        Long toId, String toName,
        BigDecimal amount, Instant createdAt) {}
```

`ClientController` exposes it as **`GET /clients/{id}/transactions`** (see [API Reference](#get-clientsidtransactions)), a thin delegate like every other route.

`getClientHistory` is not `@Transactional`: the existence check and the history query are two separate reads. Nothing is lazily loaded after the query returns — both parties are already fetched — so the mapping to `TransactionResponse` does not depend on an open session.

> ⚠️ **The query does not use the `V5` indexes.** Because the filter references the *fetched* aliases (`t.from.id`) rather than the transaction's own foreign-key columns, PostgreSQL applies the `OR` as a join filter after joining, and falls back to a full scan of `transaction`. Measured on 40,005 rows where only 5 belong to the client in question:
>
> | Filter written as | Plan | Time |
> |---|---|---|
> | `t.from.id = ? or t.to.id = ?` (current) | Seq Scan, 40,000 rows discarded by join filter | 8.76 ms |
> | `from_id = ? or to_id = ?` (FK columns) | BitmapOr over `idx_transaction_from_id` + `idx_transaction_to_id` | 0.12 ms |
>
> That is roughly 70× on a small table, and the gap widens with volume: the current plan costs a scan of the *whole* table, while the indexed plan costs only the matching rows. The two indexes `V5` created for exactly this lookup are currently never consulted. Restructuring so the predicate lands on the FK columns — for instance selecting the matching ids in a subquery and fetching the associations around it — restores the index scan while keeping the eager fetch.

#### Searching history — `TransactionRepository.search` (not wired up, currently broken)

A filtered variant of the history query has been added, but **no endpoint calls it and no test covers it** — and, as shown below, every call to it currently fails.

The criteria arrive as a new record, `dto/TransactionFilter`, in which every field is optional:

```java
public record TransactionFilter(
        BigDecimal minAmount,
        BigDecimal maxAmount,
        LocalDate from,
        LocalDate to,
        Long counterpartyId) {}
```

`ClientService.searchTransactions(id, filter)` checks the client exists and is open via `findClientById` (unknown or closed → `ClientNotFoundException`, as for plain history), converts the two dates to instants, and passes everything to one JPQL query that makes each filter optional with the `(:param is null or …)` pattern:

```java
where (t.from.id = :clientId or t.to.id = :clientId)
  and (:minAmount is null or t.amount >= :minAmount)
  and (:maxAmount is null or t.amount <= :maxAmount)
  and (:fromInstant is null or t.createdAt >= :fromInstant)
  and (:toInstant is null or t.createdAt < :toInstant)
  and (:counterpartyId is null or t.from.id = :counterpartyId or t.to.id = :counterpartyId)
order by t.createdAt desc
```

It fetches both parties and orders newest first, exactly like `findHistoryForClient`, and results map through the same `toTransactionResponse`.

| Filter | Meaning | Bound |
|---|---|---|
| `minAmount` | `amount >= minAmount` | inclusive |
| `maxAmount` | `amount <= maxAmount` | inclusive |
| `from` | `createdAt >=` **00:00 UTC** on that date | inclusive |
| `to` | `createdAt <` **00:00 UTC the day after** that date | inclusive of the whole day |
| `counterpartyId` | the other side of the transfer, in either direction | — |

> ⚠️ **Every call fails on PostgreSQL** — with no filters, with all filters, and anything in between:
>
> ```
> InvalidDataAccessResourceUsageException:
>   ERROR: could not determine data type of parameter $7
> ```
>
> Hibernate expands each named parameter into a separate positional one, so `:fromInstant is null` becomes a bare `? is null`. Parameter `$7` is that first `Instant` occurrence: the driver sends it without a declared type (whether its value is null or not), and a bare `? is null` gives PostgreSQL nothing to infer one from, so the statement is rejected when it is prepared. The `BigDecimal` and `Long` parameters in the same positions do not have this problem. Verified against the Testcontainers PostgreSQL.
>
> Only the two date checks need to change. Writing them as `cast(:fromInstant as Instant) is null` and `cast(:toInstant as Instant) is null` makes the query run, and gives the results below.

Behavior with that one change applied, on the `V6` seed data for client 1 (68 rows), matched against equivalent hand-written SQL:

| Filter | Rows |
|---|---|
| none | 68 — identical to `findHistoryForClient` |
| `minAmount = 1000` | 9 |
| `maxAmount = 50` | 14 |
| `minAmount = maxAmount = 83.25` | 1 — both bounds inclusive |
| `minAmount = 500`, `maxAmount = 100` | 0 — no error for an inverted range |
| `counterpartyId = 2` | 34 |
| `counterpartyId = 1` (the client itself) | **68 — every row, not zero** |
| `counterpartyId = 4` or `999` | 0 — no error for a client with no shared transfers, or one that does not exist |
| `from = to =` today | 1 |
| `from` later than `to` | 0 — no error |
| `minAmount = 100`, `maxAmount = 1000`, last 90 days, `counterpartyId = 3` | 7 |

Three behaviors are worth knowing before this reaches an endpoint:

- **A client named as its own counterparty disables the filter.** The client is always one side of every row, so `t.from.id = :counterpartyId or t.to.id = :counterpartyId` is true for all of them. The answer is the unfiltered history rather than an empty list.
- **Dates are UTC calendar days.** `LocalDate` is turned into an instant with `ZoneOffset.UTC`, not the caller's or the server's zone. Verified: a transfer made at `2026-01-11 00:30` in UTC+2 is `2026-01-10 22:30` UTC, so it is returned for `from = to = 2026-01-10` and not for `2026-01-11`.
- **`filter` itself must not be `null`.** `searchTransactions` calls `filter.from()` straight away, so a `null` filter throws `NullPointerException`; "no filters" means a `TransactionFilter` with all fields `null`.

`TransactionFilter` carries no validation annotations (no `@Positive` on the amounts, and no check that `from` is not after `to`), and its `LocalDate` fields have no `@DateTimeFormat`. How it will bind from query parameters depends on the endpoint that eventually exposes it.

> ⚠️ **`createdAt` is `null` on a freshly persisted instance.** Because the column is `insertable = false`, Hibernate omits it from the `INSERT` and does not read it back, so the value exists in the database but not on the object in memory. Verified against a real database: after `persist` + `flush` the entity has its generated `id` but `createdAt == null`; only after a refresh or reload does it populate.
>
> This is harmless today because nothing returns a transaction straight after creating it — `transfer` discards the saved instance, and `getClientHistory` re-reads from the database, where the timestamp is present. It becomes a bug the moment an endpoint echoes back the transaction it just wrote. Annotating the field with Hibernate's `@Generated(event = INSERT)` makes the value be selected back automatically.

| Column | Type | Constraints |
|---|---|---|
| `id` | `BIGINT` | `GENERATED ALWAYS AS IDENTITY PRIMARY KEY` |
| `type` | `VARCHAR(20)` | `NOT NULL` — no `CHECK`, so any string is accepted |
| `from_id` | `BIGINT` | `NOT NULL`, FK → `client(id)` |
| `to_id` | `BIGINT` | `NOT NULL`, FK → `client(id)` |
| `amount` | `NUMERIC(19,2)` | `NOT NULL`, `CHECK (amount > 0)` — mirrors `@Positive` on `TransferRequest` |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` — set by the database, not the application |

Plus `idx_transaction_from_id` and `idx_transaction_to_id`, which is what a "statement for one client" query would need.

Two things about the shape are worth knowing before building on it:

- **The foreign keys are safe because clients are never physically deleted.** Closing a client is a soft delete (see [Closing a client](#closing-a-client)), so `from_id`/`to_id` can never be orphaned and history survives account closure *in the database*. Over HTTP it only partly survives: a closed client's own `GET /clients/{id}/transactions` answers `404`, but its transfers still appear, with its name, in each counterparty's history. Switching to a hard delete later would break this table.
- **Both `from_id` and `to_id` are `NOT NULL`, so only two-sided movements fit.** A `type` column implies more kinds are planned, but a one-sided event (deposit, withdrawal, fee) has no second party to name — those would need a nullable column or a sentinel row.

An extra table with no matching entity does not upset `ddl-auto=validate`: Hibernate checks that each entity has a conforming table, not the reverse, so the application starts normally.

## Database & Migrations

Flyway migrations live in `src/main/resources/db/migration` and run automatically on application startup, in order:

| Version | File | What it does |
|---|---|---|
| V1 | `V1__create_client_table.sql` | Creates the `client` table (see column list above) with the `balance_non_negative` check constraint. |
| V2 | `V2__insert_seed_clients.sql` | Seeds 4 sample rows into `client`. |
| V3 | `V3__update_seed_clients.sql` | Overwrites the first/last name and phone number of the 4 seeded rows (ids 1–4) with different sample data (`John Doe`, `Jane Roe`, `Richard Miles`, `Mary Major`) — balances from `V2` are untouched. |
| V4 | `V4__add_closed_to_client.sql` | Adds the `closed BOOLEAN NOT NULL DEFAULT FALSE` soft-delete flag; the default leaves every existing row open. |
| V5 | `V5__create_transaction_table.sql` | Creates the `transaction` table with FKs to `client` and indexes on both sides. Mapped by the `Transaction` entity; every transfer now writes a row (see [Data Model](#transaction-entity-modeltransactionjava)). |
| V6 | `V6__insert_seed_transactions.sql` | Seeds 100 sample `TRANSFER` rows between clients 1, 2 and 3, backdated over the last ~181 days. Inserts into `transaction` only — **no balance is touched** (see [Seed transactions](#seed-transactions-v6)). |

**`V2` inserts, then `V3` overwrites names/phone numbers on top — net result after both run:**

| id | first_name | last_name | phone_number | balance |
|---|---|---|---|---|
| 1 | John | Doe | +12025550100 | 5000.00 |
| 2 | Jane | Roe | +12025550101 | 1200.00 |
| 3 | Richard | Miles | +12025550102 | 300.00 |
| 4 | Mary | Major | +12025550103 | 0.00 |

(`V2`'s original names — Anna Kowalska, Petro Shevchenko, Marek Nowak, Olha Melnyk — only exist transiently between the two migrations; a fresh database ends up at the table above.)

Beyond these 4 seeded rows, new clients can be added at runtime via `POST /clients` (see [API Reference](#api-reference)).

### Seed transactions (`V6`)

`V6` gives the history endpoint something to show on a fresh database: 100 transfers in one multi-row `INSERT`, cycling through the six directed pairs of clients 1–3.

| | |
|---|---|
| Rows | 100, all `type = 'TRANSFER'` |
| Parties | clients 1, 2, 3 only — client 1 appears in 68 rows, clients 2 and 3 in 66 each; **client 4 has no history** |
| Amounts | `9.40` to `4888.04`, total volume `67,243.85` |
| Time span | `now() - 180 days 20 hours` for the oldest row to `now() - 8 hours` for the newest |
| `id` | not supplied, so `GENERATED ALWAYS` assigns 1–100 in file order; the first real transfer on a fresh database gets `id` 101 |

Three properties of the script matter when relying on it:

- **`created_at` is relative to when the migration ran.** `now()` is evaluated once, when Flyway executes `V6`, so every database gets a different set of absolute timestamps, and the history keeps ageing afterwards — on a database migrated months ago, the "last 180 days" are long gone. The file is still byte-for-byte stable, so Flyway's checksum never changes.
- **Row order and time order agree.** Each row is strictly older than the next, so `id` and `created_at` sort the same way and the newest-first history is also highest-`id`-first.
- **The ledger does not add up to the balances.** `V6` never updates `client`, so balances stay at the `V2` values while the history claims large net movements:

| Client | Net movement in `V6` | Balance after `V6` | Opening balance the history would imply |
|---|---|---|---|
| 1 John Doe | −12,298.62 | 5000.00 | 17,298.62 |
| 2 Jane Roe | +3,249.28 | 1200.00 | **−2,049.28** |
| 3 Richard Miles | +9,049.34 | 300.00 | **−8,749.34** |

Replaying the history against the seeded balances instead, 35 of the 100 transfers would be rejected by `Client.withdraw` — the second row already has Jane Roe send `4104.41` while holding `1392.40`. So the sample history is illustrative only: it is not something the application itself could have produced, and summing a client's history does not reproduce its balance.

To reset the database from scratch locally:

With Docker Compose, delete the data volume and start over:

```bash
docker compose down -v && docker compose up -d
./mvnw spring-boot:run   # Flyway runs V1 through V6 on startup
```

Against a server you manage yourself:

```bash
psql -U postgres -c "DROP DATABASE IF EXISTS banking;"
psql -U postgres -c "CREATE DATABASE banking;"
./mvnw spring-boot:run
```

## Configuration

Configuration is split across two files: a base `application.properties` that always applies, and an optional `dev` profile that adds SQL logging.

### Base — `src/main/resources/application.properties`

```properties
spring.application.name=banking

spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:banking}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD:postgres}

spring.jpa.hibernate.ddl-auto=validate
```

Connection settings are externalized as environment variables with `${VAR:default}` fallbacks, so no credentials need to be edited into the file to run locally, and the same build can be pointed at another database without a rebuild:

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `banking` | Database name |
| `DB_USER` | `postgres` | Username |
| `DB_PASSWORD` | `postgres` | Password |

```bash
DB_USER=tsl DB_PASSWORD=secret ./mvnw spring-boot:run   # override without touching the file
```

`spring.jpa.hibernate.ddl-auto=validate` means Hibernate validates the entity ↔ table mapping at startup but never creates or alters schema — all schema changes go through Flyway. Note there is no `spring.datasource.driver-class-name`: Spring Boot infers `org.postgresql.Driver` from the `jdbc:postgresql:` URL prefix, so declaring it is redundant.

### `dev` profile — `src/main/resources/application-dev.properties`

```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```

SQL logging is **off by default** and only switches on when the `dev` profile is active:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# or, for a packaged jar:
SPRING_PROFILES_ACTIVE=dev java -jar target/banking-0.0.1-SNAPSHOT.jar
```

### Profiles and tests

There is no `application-test.properties` or `test` profile. `BankingApplicationTests` runs with the base configuration, except that Testcontainers' `@ServiceConnection` overrides `spring.datasource.*` at runtime to point at a throwaway PostgreSQL container — so the `DB_*` variables are irrelevant during tests (see [Testing](#testing)). `ddl-auto=validate` and the Flyway defaults still apply there, which is what makes the context test meaningful.

## Running the App

**Prerequisites**

- JDK 21
- A PostgreSQL server with a `banking` database, reachable with the settings in [Configuration](#configuration) (defaults: `localhost:5432`, user `postgres`, password `postgres`). The bundled Docker Compose file provides exactly that — see below.
- No global Maven install required — use the bundled wrapper
- Docker is needed for the Compose database and for the tests (see [Testing](#testing)), but not to run the app itself against an existing PostgreSQL

### Starting PostgreSQL with Docker Compose

`docker-compose.yml` at the project root stands up a matching database:

```bash
docker compose up -d          # start Postgres in the background
docker compose ps             # check it reports (healthy)
docker compose down           # stop it, keeping the data
docker compose down -v        # stop it and delete the data volume
```

| Setting | Value |
|---|---|
| Image | `postgres:16-alpine` (same major version the tests use) |
| Container name | `banking-postgres` |
| Database / user / password | `banking` / `postgres` / `postgres` |
| Host port | `5432` |
| Data volume | named volume `banking-data`, so data survives `down` but not `down -v` |
| Healthcheck | `pg_isready -U postgres -d banking`, every 5s |

Its environment matches the defaults in `application.properties` exactly, so with Compose running you need no `DB_*` variables at all — `./mvnw spring-boot:run` connects as-is.

> **Port conflict:** the file publishes host port `5432`. If you already run PostgreSQL locally on that port, `docker compose up` fails with "port is already allocated". Either stop the local server, or publish a different host port (e.g. `"55432:5432"`) and start the app with `DB_PORT=55432`.

**Steps**

1. Start a database — either `docker compose up -d`, or create one on an existing server:
   ```bash
   createdb banking
   ```
2. If your PostgreSQL doesn't match the defaults, export the relevant `DB_*` variables instead of editing the file:
   ```bash
   export DB_USER=tsl DB_PASSWORD=secret
   ```
3. Start the app:
   ```bash
   ./mvnw spring-boot:run
   ```
   On Windows: `mvnw.cmd spring-boot:run`. Add `-Dspring-boot.run.profiles=dev` to see the SQL it runs.
4. The API is available at `http://localhost:8080`. Flyway applies any pending migrations (V1 table creation, V2 seed data, V3 seed-data overwrite, V4 soft-delete column, V5 transaction table, V6 seed transactions) automatically before the app finishes starting.

**Building a runnable jar**

```bash
./mvnw clean package
java -jar target/banking-0.0.1-SNAPSHOT.jar
```

## API Documentation

Interactive Swagger UI is available at `http://localhost:8080/swagger-ui.html` while the application is running.

The raw OpenAPI 3 description is at `http://localhost:8080/v3/api-docs`.

Both are generated by `springdoc-openapi-starter-webmvc-ui`; no annotations are needed on the controller, as springdoc reads the existing Spring MVC mappings and DTO records. `config/OpenApiConfig.java` supplies the document metadata:

```java
@Bean
public OpenAPI bankingOpenAPI() {
    return new OpenAPI().info(new Info()
            .title("Banking API")
            .description("REST API for managing bank clients and transferring money between accounts")
            .version("1.0.0"));
}
```

The emitted document is **OpenAPI 3.1.0** and covers all nine operations and all six DTO schemas (`TransactionResponse` included — its `type` is rendered as a string enum `["TRANSFER"]` and `createdAt` as `date-time`). `/swagger-ui.html` is a convenience path — it answers `302` and redirects to `/swagger-ui/index.html`, which is where the UI is actually served.

> ⚠️ **The generated spec is an explorer, not the full contract.** Three things it does not capture, so this README remains authoritative:
>
> - **`POST /clients` is documented as `200`, but really returns `201`.** The status is built at runtime by `ResponseEntity.created(...)`, which springdoc cannot infer statically. The four `@ResponseStatus(NO_CONTENT)` endpoints *are* reported correctly as `204`.
> - **No error responses are described at all** — none of the `400`, `404` or `409` outcomes (including the `404` from the history route), nor the RFC 7807 body they carry (see [Error Handling](#error-handling)).
> - **Only some constraints survive.** `@NotBlank`/`@NotNull` become `required` plus `minLength: 1`, but `@Positive` and `@PositiveOrZero` produce no `minimum` — `balance` and `amount` appear as a bare `number`, so the spec does not say they must be non-negative.
>
> Adding `@ApiResponse`/`@Operation` annotations to `ClientController` would close all three.

## API Reference

Base path: `/clients`. No authentication, no pagination, no content negotiation beyond JSON.

### Status codes at a glance

| Endpoint | Success | Possible errors |
|---|---|---|
| `GET /clients` | `200` + array | — |
| `GET /clients/{id}` | `200` + object | `404` |
| `POST /clients` | `201` + object + `Location` | `400` |
| `PATCH /clients/{id}/phoneNumber` | `204` | `400`, `404` |
| `PATCH /clients/{id}/lastName` | `204` | `404` |
| `PUT /clients/{id}` | `204` | `400`, `404` |
| `DELETE /clients/{id}` | `204` | `404`, `409` |
| `GET /clients/{id}/transactions` | `200` + array | `404` |
| `POST /clients/transfer` | `204` | `400`, `404`, `409` |

Mutating endpoints return `204 No Content` with no body, so a client that needs the updated state must issue a follow-up `GET`. `POST /clients` is the exception: it returns the created representation.

### Request validation

Request bodies are validated with Jakarta Bean Validation before any handler runs. Constraints live on the DTO records, and controller parameters are annotated `@Valid`:

| DTO | Field | Constraints | Rejection message |
|---|---|---|---|
| `ClientRequest` | `firstName` | `@NotBlank` | `must not be blank` |
| | `lastName` | *(none — column is nullable)* | |
| | `balance` | `@NotNull` `@PositiveOrZero` | `must not be null` / `must be greater than or equal to 0` |
| | `phoneNumber` | *(none — column is nullable)* | |
| `PhoneNumberRequest` | `phoneNumber` | `@NotBlank` | `must not be blank` |
| `TransferRequest` | `fromId`, `toId` | `@NotNull` | `must not be null` |
| | `amount` | `@NotNull` `@Positive` | `must not be null` / `must be greater than 0` |
| `LastNameRequest` | `lastName` | *(none, and the endpoint has no `@Valid`)* | |

The constraints deliberately mirror the DB schema — `@NotBlank firstName` matches `first_name NOT NULL`, and `@PositiveOrZero balance` matches the `balance_non_negative` check constraint — so those violations are now rejected as clean `400`s instead of reaching Postgres and surfacing as a `500`.

A validation failure returns **`400 Bad Request`** as an RFC 7807 problem document (see [Error Handling](#error-handling)), with the individual field errors nested under an `errors` extension member:

```json
{
  "detail": "Request validation failed",
  "instance": "/clients",
  "status": 400,
  "title": "Validation error",
  "errors": {
    "firstName": "must not be blank",
    "balance": "must be greater than or equal to 0"
  }
}
```

### `GET /clients`

Returns every client.

```bash
curl http://localhost:8080/clients
```

**Response — `200 OK`**

```json
[
  { "id": 1, "firstName": "John", "lastName": "Doe", "balance": 5000.00 }
]
```

### `GET /clients/{id}`

Returns a single client.

```bash
curl http://localhost:8080/clients/1
```

**Response — `200 OK`**

```json
{ "id": 1, "firstName": "John", "lastName": "Doe", "balance": 5000.00 }
```

**Response — `404 Not Found`** if `id` doesn't exist:

```json
{
  "detail": "Client not found: 1",
  "instance": "/clients/1",
  "status": 404,
  "title": "Client not found"
}
```

### `POST /clients`

Creates a new client. `id` is DB-generated (`IDENTITY`) — do not include it in the request.

```bash
curl -i -X POST http://localhost:8080/clients \
  -H "Content-Type: application/json" \
  -d '{ "firstName": "Emily", "lastName": "Carter", "balance": 250.00, "phoneNumber": "+12025550104" }'
```

**Response — `201 Created`**, with an absolute `Location` header (e.g. `http://localhost:8080/clients/5`) pointing at the new resource:

```json
{ "id": 5, "firstName": "Emily", "lastName": "Carter", "balance": 250.00 }
```

**Response — `400 Bad Request`** if `firstName` is blank/missing or `balance` is null/negative (see [Request validation](#request-validation)):

```json
{ "firstName": "must not be blank" }
```

`lastName` and `phoneNumber` are optional, matching their nullable columns. Note that string *length* is still unguarded — a `firstName` over 100 chars or a `phoneNumber` over 20 chars passes validation and fails in Postgres as a `500` (see [Known Issues](#known-issues--limitations)).

### `PATCH /clients/{id}/phoneNumber`

Updates a client's phone number. Returns `204 No Content` on success.

```bash
curl -X PATCH http://localhost:8080/clients/1/phoneNumber \
  -H "Content-Type: application/json" \
  -d '{ "phoneNumber": "+10000000000" }'
```

`phoneNumber` is `@NotBlank`, so a blank or missing value returns `400`. A non-existent `{id}` returns `404`.

### `PATCH /clients/{id}/lastName`

Updates a client's last name. Returns `204 No Content` on success.

```bash
curl -X PATCH http://localhost:8080/clients/1/lastName \
  -H "Content-Type: application/json" \
  -d '{ "lastName": "Smith" }'
```

⚠️ This is the **only** body-carrying endpoint without validation: `LastNameRequest` declares no constraints and the handler has no `@Valid`, so `{ "lastName": "" }` or `{}` is accepted and will blank out / null the stored last name. Every sibling endpoint rejects the equivalent input with a `400`.

### `PUT /clients/{id}`

Replaces first name, last name, and phone number in one call. Returns `204 No Content` on success.

```bash
curl -X PUT http://localhost:8080/clients/1 \
  -H "Content-Type: application/json" \
  -d '{ "firstName": "Sarah", "lastName": "Smith", "balance": 5000.00, "phoneNumber": "+12025550105" }'
```

Body constraints are the same as `POST /clients` (`firstName` `@NotBlank`, `balance` `@NotNull @PositiveOrZero`), so the same `400` shape applies; a non-existent `{id}` returns `404`.

> ⚠️ **`balance` is required in the body but silently ignored.** This endpoint shares `ClientRequest` with `POST /clients`, where `balance` seeds the opening balance. On update, `ClientService.updateClient` no longer assigns it — `Client` has no `setBalance`, since balance may only move through `withdraw`/`deposit`. The validation annotations still apply, so you must send a valid `balance` and it will have no effect:
>
> ```
> PUT /clients/1  {"firstName":"Sarah","lastName":"Smith","balance":99999.99,"phoneNumber":"+12025550105"}
>   -> 204, and GET /clients/1 still reports the original balance
>
> PUT /clients/1  {"firstName":"Sarah","lastName":"Smith","phoneNumber":"+12025550105"}
>   -> 400 {"errors":{"balance":"must not be null"}}
> ```
>
> This is a deliberate consequence of protecting the balance invariant, not an oversight — but it makes the endpoint's contract misleading. A dedicated update DTO without a `balance` field would remove the trap.

Balances are only ever changed by `POST /clients/transfer`, which moves money between two accounts rather than setting it outright.

The path was `PUT /clients/{id}/update` in earlier revisions; the `/update` suffix was dropped so the URL identifies the resource rather than the action.

### `DELETE /clients/{id}`

Closes a client. Returns `204 No Content` on success. This is a **soft delete** — the row is retained with `closed = true` (see [Closing a client](#closing-a-client)).

```bash
curl -i -X DELETE http://localhost:8080/clients/4
```

**Response — `409 Conflict`** if the balance is not exactly zero. Empty the account with a transfer first:

```json
{
  "detail": "Cannot close client with a non-zero balance: 1",
  "instance": "/clients/1",
  "status": 409,
  "title": "Client has a non-zero balance"
}
```

**Response — `404 Not Found`** if the id does not exist *or the client is already closed*. Note this makes the endpoint **not idempotent** in the HTTP sense: a repeated `DELETE` answers `404`, not `204`.

After closing, the client vanishes from `GET /clients`, `GET /clients/{id}` returns `404`, and it can no longer be either side of a transfer — a transfer naming it fails with `Client not found`.

### `GET /clients/{id}/transactions`

Returns every money movement the client took part in — as sender *or* recipient — newest first. Each transfer therefore appears in the history of both parties.

```bash
curl http://localhost:8080/clients/3/transactions
```

**Response — `200 OK`** on a fresh database, where the list is the `V6` seed data — 66 entries for client 3, of which the two newest are shown (the timestamps depend on when the migration ran):

```json
[
  {
    "id": 100, "type": "TRANSFER",
    "fromId": 3, "fromName": "Richard Miles",
    "toId": 1, "toName": "John Doe",
    "amount": 83.25, "createdAt": "2026-09-17T05:10:45.354415Z"
  },
  {
    "id": 99, "type": "TRANSFER",
    "fromId": 1, "fromName": "John Doe",
    "toId": 3, "toName": "Richard Miles",
    "amount": 979.12, "createdAt": "2026-09-15T06:40:45.354415Z"
  }
]
```

On a fresh database clients 1, 2 and 3 return 68, 66 and 66 entries; client 4 returns `[]` until it takes part in a transfer.

| Field | Notes |
|---|---|
| `type` | Always `TRANSFER` today — the only `TransactionType` constant. |
| `fromName` / `toName` | `firstName + " " + lastName`, built in `ClientService`. A client without a last name renders as e.g. `"Cher null"` (see [Known Issues](#known-issues--limitations)). |
| `amount` | Read back from the `NUMERIC(19,2)` column, so always two decimal places (`100.00`), whatever scale the transfer request used. |
| `createdAt` | ISO-8601 UTC instant with microsecond precision, taken from the database's `now()` at the start of the transfer's transaction. `V6` rows are backdated relative to the migration's `now()` instead. |

There is no sign or direction field: whether an entry is money in or out for *this* client is only derivable by comparing `fromId`/`toId` with the `{id}` in the path.

A client with no transfers gets `200` with `[]`.

**Response — `404 Not Found`** if the id does not exist **or the client is closed** — the lookup goes through the same `findByIdAndClosedFalse` as every other route:

```json
{
  "detail": "Client not found: 3",
  "instance": "/clients/3/transactions",
  "status": 404,
  "title": "Client not found"
}
```

The list is unpaged — every row for the client comes back in one response (see [Known Issues](#known-issues--limitations)).

### `POST /clients/transfer`

Moves `amount` from `fromId`'s balance to `toId`'s balance. Returns `204 No Content` on success.

```bash
curl -X POST http://localhost:8080/clients/transfer \
  -H "Content-Type: application/json" \
  -d '{ "fromId": 1, "toId": 2, "amount": 100.0 }'
```

**Validation & errors**, in the order they are evaluated:

| # | Condition | Rejected by | HTTP Status | Body |
|---|---|---|---|---|
| 1 | `amount` null or `<= 0`, or `fromId`/`toId` null | `@Valid` (Bean Validation) | 400 Bad Request | `{ "amount": "must be greater than 0" }` |
| 2 | `fromId.equals(toId)` | `ClientService` → `IllegalArgumentException` | 400 Bad Request | title `Invalid request`, detail `Cannot transfer to the same account` |
| 3 | Either client not found | `ClientService` → `ClientNotFoundException` | 404 Not Found | title `Client not found`, detail `Client not found: <id>` |
| 4 | `from.balance < amount` | `Client.withdraw` → `InsufficientFundsException` | 409 Conflict | title `Insufficient funds`, detail `Insufficient funds` |

All four responses are RFC 7807 problem documents; only #1 carries the extra `errors` member. The same-account rule (#2) is a cross-field check that Bean Validation can't express with field-level constraints, so it stays in the service, while the amount rule (#1) moved up to the annotation layer.

`ClientService.transfer` is now only responsible for orchestration — resolving both clients, rejecting a same-account transfer, then calling `from.withdraw(amount)` and `to.deposit(amount)`. The funds check lives in `Client.withdraw` (see [Data Model](#data-model)), so the service contains no balance arithmetic at all:

```java
Client from = findClientById(request.fromId());
Client to = findClientById(request.toId());

from.withdraw(request.amount());
to.deposit(request.amount());
```

The whole transfer runs inside a single `@Transactional` service method, so if `withdraw` throws, the transaction rolls back and neither balance is modified. Both accounts are also row-locked for the duration of the transaction, in a fixed order that rules out deadlocks — see [Concurrency](#concurrency).

## Error Handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) centralizes exception → HTTP status mapping for the whole app. Every handler returns a Spring `ProblemDetail`, so all errors are **RFC 7807 / RFC 9457 problem documents** served as `Content-Type: application/problem+json`:

| Exception | Status | `title` | `detail` |
|---|---|---|---|
| `ClientNotFoundException` | `404 Not Found` | `Client not found` | `Client not found: <id>` |
| `InsufficientFundsException` | `409 Conflict` | `Insufficient funds` | `Insufficient funds` |
| `ClientHasBalanceException` | `409 Conflict` | `Client has a non-zero balance` | `Cannot close client with a non-zero balance: <id>` |
| `IllegalArgumentException` | `400 Bad Request` | `Invalid request` | *(exception message)* |
| `MethodArgumentNotValidException` | `400 Bad Request` | `Validation error` | `Request validation failed` |

A representative body — Spring fills `instance` in with the request URI automatically, and **omits `type`** because it is left at its `about:blank` default:

```json
{
  "detail": "Insufficient funds",
  "instance": "/clients/transfer",
  "status": 409,
  "title": "Insufficient funds"
}
```

All four share one envelope, so a client can parse errors generically. The validation handler is the only one that adds an extension member: it collects `getBindingResult().getFieldErrors()` into a `LinkedHashMap` (preserving field order) and attaches it via `setProperty("errors", …)`, producing the nested `errors` object shown under [Request validation](#request-validation).

Both custom exceptions are unchecked (`RuntimeException`) and build their own message in the constructor, so the throw sites read as `new ClientNotFoundException(id)` / `new InsufficientFundsException()`.

Any other unhandled exception (e.g. a database connectivity failure, a malformed JSON body, or a `DataIntegrityViolationException` from exceeding a column's length) falls through to Spring Boot's default error handling. Those responses are *also* `application/problem+json`, but carry Spring's generic title/detail rather than a domain-specific one.

## Concurrency

Only `transfer` needs concurrency control, and it is the one operation that gets it.

**The problem.** A transfer is a read-modify-write on two rows. Two transfers touching the same account concurrently could each read a balance of 5000, each subtract 100, and each write 4900 — one debit silently lost. Because balances are mutated through JPA dirty checking rather than an atomic `UPDATE … SET balance = balance - ?`, nothing in the database prevents that interleaving on its own.

**The fix — lock the rows before reading them.** `ClientRepository` exposes a locking lookup:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Client c where c.id = :id")
Optional<Client> findByIdForUpdate(@Param("id") Long id);
```

`transfer` uses this instead of the plain `findById`, so each account row is locked for the duration of the transaction and a competing transfer blocks until the first commits. The `and c.closed = false` clause makes the same query enforce the soft-delete rule, so a closed account can't be either side of a transfer. The explicit `@Query` is required: Spring Data cannot derive a query from the name `findByIdForUpdate`, since it would try to read `IdForUpdate` as a property path.

On PostgreSQL, Hibernate renders `PESSIMISTIC_WRITE` as **`FOR NO KEY UPDATE`**, not `FOR UPDATE`:

```sql
select c1_0.id, c1_0.balance, c1_0.first_name, c1_0.last_name, c1_0.phone_number
from client c1_0
where c1_0.id = ?
for no key update of c1_0
```

That is the weaker of the two row-exclusive modes — it still blocks another `FOR NO KEY UPDATE`/`FOR UPDATE` on the same row, which is all that matters here, while leaving `FOR KEY SHARE` (foreign-key checks) unblocked.

**Deadlock avoidance.** Locking two rows invites the classic cycle: a transfer `1 → 2` and a concurrent `2 → 1` could each hold one row and wait forever for the other. `transfer` prevents this by always acquiring locks in ascending id order, regardless of transfer direction:

```java
if (fromId < toId) {
    from = lockClientById(fromId);
    to = lockClientById(toId);
} else {
    to = lockClientById(toId);
    from = lockClientById(fromId);
}
```

Since every transfer takes the lower id first, no cycle can form.

**Scope.** `getClientById`, `updatePhoneNumber`, `updateLastName`, and `updateClient` still use the non-locking `findClientById`. That is safe because none of them touch `balance` any more (see [Data Model](#data-model)) — the only field with a concurrency-sensitive invariant is reached solely through `transfer`.

## Testing

```bash
./mvnw test
```

**Requirements:** a running Docker daemon. You do *not* need a local PostgreSQL — the Testcontainers-backed tests start their own throwaway `postgres:16-alpine` containers, so `./mvnw test` is self-contained and safe to run against a machine with no `banking` database (and it never touches your local data).

Current state: **25 tests, all passing** (15 unit, 4 controller-slice, 5 integration, 1 context).

| Test class | Type | Coverage |
|---|---|---|
| `BankingApplicationTests` | Integration (`@SpringBootTest` + `@Testcontainers`) | `contextLoads()` — boots the full application context against a disposable PostgreSQL container. Because startup runs Flyway and then `ddl-auto=validate`, this single test transitively proves that **V1→V6 apply cleanly to an empty database** and that the resulting schema **matches the `Client` entity**. |
| `service.ClientServiceTest` | Unit (Mockito-mocked `ClientRepository`, no DB) | `getAllClients` (mapping, size); `getClientById` plus its `ClientNotFoundException` path; `updatePhoneNumber`; `updateLastName`; `updateClient`; `createClient` (returns the saved client's id and mapped fields); `transfer` — happy path, same-account and insufficient-funds cases, and `transfer_savesTransactionRecord`, which verifies a `Transaction` is passed to `transactionRepository.save(...)`; `closeClient` with a zero and a non-zero balance. Balance assertions use AssertJ's `isEqualByComparingTo` (scale-independent `BigDecimal` comparison) rather than `isEqualTo`. |
| `service.ClientServiceIntegrationTest` | Integration (`@SpringBootTest` + `@Testcontainers`) | Drives the real `ClientService` against a real database: a transfer moves money and **both balances survive the commit**; a failed transfer (insufficient funds) **leaves both balances unchanged**, proving the rollback; closing a client (after draining its balance) **removes it from the listing and makes it read as not-found**; a transfer `1 → 2` **appears at the top of client 1's history** with the right ids, amount, a non-null `createdAt` and a resolved `fromName`, and shows up in client 2's history too; and `getClientHistory` for an unknown id throws `ClientNotFoundException`. |
| `controller.ClientControllerTest` | Web slice (`@WebMvcTest(ClientController.class)` + `@MockitoBean` service) | Exercises the HTTP layer with MockMvc and no database: `404` + problem-document `status`/`detail` for an unknown id; `400` with `errors.amount` for a negative amount; `400` with `errors.{fromId,toId,amount}` for an empty body; `409` when the service reports insufficient funds. |

Test methods follow a `method_whenCondition_expectedBehavior` naming convention (e.g. `transfer_whenInsufficientFunds_throwsInsufficientFunds`).

`withdraw_whenInsufficientFunds_throwsAndLeavesBalanceUnchanged` exercises the `Client` entity directly with no mocks — it constructs a `Client` and asserts the balance is untouched after a rejected withdrawal. It currently lives in `ClientServiceTest` despite testing the model rather than the service; a separate `ClientTest` would be the natural home as more domain behavior moves into the entity.

The negative- and zero-amount transfer tests were removed from `ClientServiceTest` when validation moved to the DTOs: those inputs are rejected by `@Positive` at the controller boundary, which a service-level unit test can't reach. `ClientControllerTest` now covers that boundary instead — `transfer_whenAmountIsNegative_returns400` is the test that proves `@Valid` is actually wired up, and it asserts on `$.errors.amount`, so it also pins the problem-document shape described in [Error Handling](#error-handling).

**Two deliberate choices in `ClientServiceIntegrationTest` are worth preserving:**

- **It is not `@Transactional`.** Annotating the test class would wrap each test in a transaction that rolls back at the end — which would silently destroy the very thing these tests exist to prove. Because they commit for real, they demonstrate that dirty checking actually flushes.
- **It reads balances before acting** rather than asserting absolute amounts. The tests share a database with each other, and the money-moving test commits, so hard-coding "5000.00" would make them order-dependent. Capturing `fromBefore`/`toBefore` and asserting a *delta* keeps them independent.

They do depend on the Flyway seed data, though: ids 1, 2 and 4 must exist, and **client 4 must have a zero balance** for the insufficient-funds case (`V2` seeds it at `0.0`; `V3` renames it to Mary Major without touching the balance). Changing the seed migrations can break these tests — see [Database & Migrations](#database--migrations).

`V6` changes nothing for the balance tests, because it never touches `client`. It does mean every test container now starts with 68/66/66 history rows for clients 1/2/3 — which the history test only survives because its own transfer is timestamped after the backdated seed rows (the newest is `now() - 8 hours`).

**How the container is wired up** (`BankingApplicationTests`):

```java
@Testcontainers
@SpringBootTest
class BankingApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
```

`@Container` manages the container lifecycle (started once for the class, torn down after), and `@ServiceConnection` auto-configures `spring.datasource.*` from it — no manual `@DynamicPropertySource` URL/credential wiring needed. Note `PostgreSQLContainer` is *not* parameterized: Testcontainers 2.x dropped the self-referential generic that 1.x required, and the class now lives in `org.testcontainers.postgresql` (not `org.testcontainers.containers`).

`ClientServiceIntegrationTest` declares its own identical container block. Because each class owns a separate `@Container` field, the two get **two different Spring contexts and two PostgreSQL containers per run** — visible in the build log as two `Creating container for image: postgres:16-alpine` lines. That costs roughly a second of startup and is the obvious next optimization: hoisting the container onto a shared abstract base class (or a `@TestConfiguration` with `@ServiceConnection`) would let both classes reuse one container and one cached context.

**Remaining gaps**

- **`TransactionRepository.search` / `ClientService.searchTransactions` have no tests at all** — which is how a query that fails on every call went unnoticed while the suite stays green. A single integration test calling it with an empty `TransactionFilter` would have caught it.
- **`GET /clients/{id}/transactions` has no controller-slice test.** The service method is covered by integration tests, but nothing asserts the HTTP mapping — the `200` array shape or the `404` problem document.
- **The history tests are shallow in places.** `transfer_savesTransactionRecord` matches `any(Transaction.class)`, so it would pass if the wrong parties, amount or type were recorded; the integration test's two `isNotEmpty()` checks on the histories of clients 1 and 2 are now **always true before the transfer even happens**, because `V6` seeds both — so they no longer prove anything, and only the `getFirst()` assertions on client 1's history test the new row; and there is no unit test for `getClientHistory` or for the `TransactionResponse` mapping (including the `null` last-name case). The integration test's `getFirst()` check is reliable despite the shared database only because the test's own transfer is the most recent one.
- **No test covers history for a closed client** — neither the `404` on its own history nor its transfers remaining visible to a counterparty.
- **Lock *contention* is still unproven.** `ClientServiceIntegrationTest` now runs `findByIdForUpdate` against a real PostgreSQL, so the locking query is known to be valid and to acquire a row lock. What no test does is run **two concurrent transactions**, which is what would actually demonstrate that one transfer blocks the other and that the ascending-id ordering prevents a deadlock (see [Concurrency](#concurrency)). That needs a test driving two threads with a barrier between them.
- Only the `transfer` paths are covered end-to-end. `POST /clients` (`201` + `Location` header), the two `PATCH` endpoints and `PUT /clients/{id}` have no controller-slice or integration coverage — including the required-but-ignored `balance` behavior on `PUT`, which is the project's most surprising contract and rests on documentation alone.
- `createClient`'s unit test only covers mapping a repository-returned `Client` to a `ClientResponse` — it doesn't assert *what* gets passed to `repository.save(...)` (e.g. that `id` is `null` going in).
- `spring-boot-starter-data-jpa-test` is still unused — the new tests use `@SpringBootTest` and `@WebMvcTest`, not `@DataJpaTest`, so nothing pulls in that starter. Either add a repository slice test or drop the dependency.

The Maven Surefire plugin is configured with an explicit Mockito Java agent (`-javaagent:.../mockito-core-5.14.2.jar`) and `-Xshare:off`, required for Mockito's inline mock maker to work under recent JDKs.

## Continuous Integration

`.github/workflows/build.yml` runs the build on every push to `main` and every pull request targeting `main`. The badge at the top of this file reflects the latest run on `main`.

```yaml
runs-on: ubuntu-latest
steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
    with:
      java-version: '21'
      distribution: 'temurin'
      cache: maven
  - run: ./mvnw --batch-mode verify
```

| Aspect | Detail |
|---|---|
| Runner | `ubuntu-latest` |
| JDK | 21, Temurin distribution |
| Dependency cache | `cache: maven` — `setup-java` caches `~/.m2/repository`, keyed on the POM |
| Command | `./mvnw --batch-mode verify` |

`verify` runs the full lifecycle up to and including packaging, so CI compiles, executes all 25 tests, builds the jar, and repackages it as a Spring Boot executable archive — a stricter gate than `test` alone.

Two details make this work without extra setup:

- **Testcontainers needs a Docker daemon**, and `ubuntu-latest` ships with one preinstalled, so `BankingApplicationTests` starts its `postgres:16-alpine` container on the runner exactly as it does locally. No service container or database configuration is declared in the workflow, and none is needed — see [Testing](#testing). A runner without Docker (some self-hosted setups) would fail this build.
- **`mvnw` is committed with its executable bit set** (mode `100755`), so `./mvnw` runs directly on the runner without a `chmod +x` step.

## Build Tooling

- `./mvnw` / `mvnw.cmd` — Maven Wrapper, pinned to Maven 3.9.16 via `.mvn/wrapper/maven-wrapper.properties` (`wrapperVersion=3.3.4`, `distributionType=only-script`). No local Maven install is required.
- `.gitattributes` forces LF line endings for `mvnw` and CRLF for `*.cmd` files, so the wrapper scripts behave correctly regardless of the contributor's OS/git config.
- `pom.xml` declares a real `<name>` and `<description>`. The empty `<url>`, `<licenses>`, `<developers>`, and `<scm>` placeholders that Spring Initializr generates have been removed, so those elements are now **inherited** from `spring-boot-starter-parent` (Apache License 2.0, the Spring team, and Spring Boot's SCM URLs). That only surfaces in the effective POM (`./mvnw help:effective-pom`) and in published artifact metadata; re-add them as empty self-closing tags to suppress the inheritance.
- `.github/workflows/build.yml` is the only CI configuration — see [Continuous Integration](#continuous-integration).
- `docker-compose.yml` provisions the local PostgreSQL only — the application itself is not containerized, so there is no `Dockerfile` and no app service in the Compose file. `docker compose up -d` then `./mvnw spring-boot:run` is the intended local loop (see [Running the App](#starting-postgresql-with-docker-compose)).
- The Spring Boot Maven plugin excludes Lombok from the final packaged jar (it's a compile-time-only, `optional` dependency).

## Known Issues & Limitations

- **Validation gaps that remain**: Bean Validation now covers null/blank/sign, but not **string length** — `first_name`/`last_name` are `VARCHAR(100)` and `phone_number` is `VARCHAR(20)` with no matching `@Size`, so an over-long value still reaches Postgres and surfaces as an unhandled `DataIntegrityViolationException` (500). There is also no format check on `phoneNumber` (any non-blank string ≤20 chars is accepted).
- **`PATCH /clients/{id}/lastName` is unvalidated**: `LastNameRequest` has no constraints and the handler has no `@Valid`, so a blank or absent `lastName` silently blanks the stored value while every other endpoint rejects the equivalent input (see [API Reference](#patch-clientsidlastname)).
- **`type` is never set on problem documents**: every error leaves `type` at the default `about:blank` (so Spring omits it), meaning `title` is the only machine-readable discriminator between, say, a not-found and an insufficient-funds `409`. Assigning stable `type` URIs would let clients branch on an identifier rather than on display text.
- **`ClientRequest` serves two endpoints with different semantics**: `balance` seeds the opening balance on `POST /clients`, but is required-and-ignored on `PUT /clients/{id}` (see [API Reference](#put-clientsid)). Splitting it into `CreateClientRequest` / `UpdateClientRequest` would make both contracts honest.
- **`updateClient_updatesAllFields` is now misnamed**: it no longer asserts anything about balance, because the endpoint no longer changes it — the name promises more than the test checks.
- **`createClient`'s test doesn't verify the saved entity**: it stubs `repository.save(any(Client.class))` and only checks the returned `ClientResponse`, so a bug that dropped a field before calling `save` (e.g. forgetting to copy `phoneNumber`) wouldn't be caught. `POST /clients` also has no controller-slice or integration coverage, so its `201` and `Location` header are untested (see [Testing](#testing)).
- **History search fails on every call**: `TransactionRepository.search` is rejected by PostgreSQL with `could not determine data type of parameter $7`, because the `:fromInstant is null` / `:toInstant is null` checks give the database no type for an `Instant` parameter. Nothing calls it yet, so the application runs normally — it becomes a `500` as soon as an endpoint does. Casting both checks (`cast(:fromInstant as Instant) is null`) makes it run (see [Data Model](#searching-history--transactionrepositorysearch-not-wired-up-currently-broken)).
- **History search has edge cases to settle before it is exposed**: naming the client as its own `counterpartyId` returns the full history rather than nothing; date filters use UTC calendar days regardless of the caller's zone; inverted amount or date ranges silently return `[]`; a `null` filter throws `NullPointerException`; and `TransactionFilter` has no validation.
- **Two queries overlap**: `search` with no filters returns exactly what `findHistoryForClient` does, so the history endpoint could be served by `search` and the older query removed, once `search` works.
- **A closed client's history is unreachable**: `GET /clients/{id}/transactions` resolves the client through the closed-filtering lookup, so once an account is closed its own statement answers `404`, even though every row survives in the `transaction` table. Its transfers stay visible only from the counterparty's side.
- **History entries carry no direction**: the response has no in/out indicator or signed amount, so a consumer has to compare `fromId` with the requested id to know whether money arrived or left.
- **`fromName`/`toName` render as `"Cher null"` when a client has no last name**: `TransactionResponse` builds the display name with `getFirstName() + " " + getLastName()`, and `last_name` is nullable with no `@NotBlank` on `ClientRequest.lastName` — so a client created without one is reachable through the public API and its name renders with a literal `null`. Verified end-to-end through `GET /clients/{id}/transactions`.
- **`findHistoryForClient` bypasses the `V5` indexes**: filtering on the fetch-joined aliases makes PostgreSQL scan the whole `transaction` table instead of using `idx_transaction_from_id`/`idx_transaction_to_id` — measured at ~70× slower on 40k rows, degrading further as volume grows. Now that `GET /clients/{id}/transactions` calls it, this is on a live, public path (see [Data Model](#reading-history--transactionrepository)). The new `search` query filters on the same aliases (Hibernate renders `f1_0.id=? or t2_0.id=?`), so it inherits the problem, and its optional-filter pattern makes a well-indexed plan harder still.
- **Seeded history contradicts seeded balances**: `V6` inserts 100 transfers without adjusting any balance, so a client's history does not sum to its balance, and replayed in order it would drive clients 2 and 3 negative — something the application forbids (see [Seed transactions](#seed-transactions-v6)). Anything that reconciles balances against the ledger will report every seeded client as wrong.
- **Sample data ships in versioned migrations**: `V2`, `V3` and now `V6` are ordinary Flyway migrations, so any database this application is pointed at — including a production one — receives four fake clients and 100 fake transfers between them. Moving seed scripts to a separate location enabled only for local profiles (e.g. `spring.flyway.locations` per profile) would keep them out.
- **Transaction history is unbounded**: `findHistoryForClient` returns every row for a client with no paging or limit, and `GET /clients/{id}/transactions` serializes the whole list, so a long-lived account loads and sends its entire ledger in one response. Combined with the unindexed query above and the lack of authentication, this is also the cheapest endpoint to make expensive.
- **Closing is irreversible and retains all personal data**: `close()` is one-way — no endpoint reopens an account — and the soft delete keeps the name and phone number in the `client` table indefinitely. For an API holding personal data that is a retention decision worth making deliberately, and it means `DELETE /clients/{id}` does *not* satisfy a request to erase someone's data.
- **`DELETE /clients/{id}` is not idempotent**: repeating it returns `404` rather than `204`, because a closed client is indistinguishable from a missing one (see [API Reference](#delete-clientsid)).
- **No authentication/authorization**: every endpoint is unauthenticated and unauthorized — anyone who can reach port 8080 can read all client data and move money between any two accounts.
- **`spring.jpa.open-in-view` is enabled by default**: Spring logs a warning about this on every startup. It keeps the Hibernate session open for the whole request, which can hide lazy-loading issues and hold DB connections longer than necessary; it's worth setting explicitly to `false`.
- **The generated OpenAPI spec is incomplete**: springdoc now publishes Swagger UI and an OpenAPI 3.1 document, but it reports `200` for `POST /clients` (actually `201`), describes no error responses, and drops the `@Positive`/`@PositiveOrZero` bounds — see [API Documentation](#api-documentation). Until `@ApiResponse`/`@Operation` annotations are added, the spec cannot be used as the contract on its own.
- **No logging/observability beyond opt-in SQL logging**: the `dev` profile logs queries, but there's no structured application logging, metrics, or health-check endpoint (no Spring Boot Actuator dependency).
- **Credentials default to `postgres`/`postgres`**: `DB_USER`/`DB_PASSWORD` fall back to a well-known development credential pair. That's convenient locally, but any deployment that forgets to set them starts up with guessable credentials rather than failing fast — dropping the defaults (`${DB_PASSWORD}` with no fallback) would surface the misconfiguration at startup.
