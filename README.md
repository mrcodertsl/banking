# Banking

A small Spring Boot REST API for managing bank clients and transferring money between accounts.

## Table of Contents

- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Data Model](#data-model)
- [Database & Migrations](#database--migrations)
- [Configuration](#configuration)
- [Running the App](#running-the-app)
- [API Reference](#api-reference)
- [Error Handling](#error-handling)
- [Testing](#testing)
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
| Boilerplate reduction | Lombok |
| Build tool | Maven, via the included `./mvnw` / `mvnw.cmd` wrapper (Maven 3.9.16, wrapper 3.3.4 — see `.mvn/wrapper/maven-wrapper.properties`) |
| Test framework | JUnit 5, Mockito 5.14.2, AssertJ (`spring-boot-starter-webmvc-test`) |
| Integration testing | Testcontainers 2.0.5 (`spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`) — spins up a real PostgreSQL in Docker for the context test |

## Project Structure

```
src/main/java/com/roladio/banking
├── BankingApplication.java      # @SpringBootApplication entry point
├── controller/
│   └── ClientController.java    # REST endpoints, mapped under /clients
├── dto/                          # all records; most carry Bean Validation constraints
│   ├── ClientRequest.java       # POST /clients + PUT /clients/{id} body
│   ├── ClientResponse.java      # response shape for GET endpoints and POST /clients
│   ├── LastNameRequest.java     # PATCH .../lastName body (no constraints — see API Reference)
│   ├── PhoneNumberRequest.java  # PATCH .../phoneNumber body
│   └── TransferRequest.java     # POST /clients/transfer body
├── exceptions/
│   ├── ClientNotFoundException.java     # unchecked; carries "Client not found: <id>" -> 404
│   ├── InsufficientFundsException.java  # unchecked; carries "Insufficient funds" -> 409
│   └── GlobalExceptionHandler.java      # @RestControllerAdvice mapping exceptions -> HTTP status
├── model/
│   └── Client.java               # JPA entity mapped to the `client` table
├── repository/
│   └── ClientRepository.java     # Spring Data JPA repository (JpaRepository<Client, Long>)
└── service/
    └── ClientService.java        # business logic: lookups, updates, transfers

src/main/resources/
├── application.properties        # base config; datasource via DB_* env vars
├── application-dev.properties    # `dev` profile: SQL logging only
└── db/migration/                 # Flyway migration scripts (see below)

src/test/java/com/roladio/banking
├── BankingApplicationTests.java          # context test, backed by a Testcontainers PostgreSQL
└── service/ClientServiceTest.java        # unit tests for ClientService (Mockito, no DB)
```

All DTOs are Java `record`s (immutable, no validation annotations — see [Known Issues](#known-issues--limitations)). All request/response bodies are plain JSON, there is no API versioning or content negotiation beyond the Spring Boot defaults.

`ClientController` is a thin layer: every method just delegates to `ClientService`. The mutating endpoints (`PATCH`/`PUT`/`POST /transfer`) are `void` and annotated `@ResponseStatus(HttpStatus.NO_CONTENT)`, so they answer `204`; the `GET` endpoints return a `ClientResponse` with `200`, and `POST /clients` builds its own `201` response.

`ClientService` maps entities to DTOs through a single private `toResponse(Client)` helper, and persists through **JPA dirty checking** rather than explicit saves: every mutating method is `@Transactional` and loads its entity via `findById`, so the entity is managed and Hibernate flushes the changes at commit. Only `createClient` calls `clientRepository.save(...)`, because a brand-new entity has to be made managed first. This means the absence of a `save(...)` call in `updatePhoneNumber`, `updateLastName`, `updateClient`, and `transfer` is deliberate, not an oversight.

The model is not anemic: `Client` enforces its own balance invariant through `withdraw`/`deposit` (see [Data Model](#data-model)), so the service orchestrates but never performs balance arithmetic or funds checks itself.

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

### `client` table (Postgres, created by `V1__create_client_table.sql`)

| Column | Type | Constraints |
|---|---|---|
| `id` | `BIGINT` | `GENERATED ALWAYS AS IDENTITY PRIMARY KEY` |
| `first_name` | `VARCHAR(100)` | `NOT NULL` |
| `last_name` | `VARCHAR(100)` | nullable |
| `phone_number` | `VARCHAR(20)` | nullable |
| `balance` | `NUMERIC(19,2)` | `NOT NULL DEFAULT 0`, plus `CHECK (balance >= 0)` |

`spring.jpa.hibernate.ddl-auto=validate` (see [Configuration](#configuration)) means Hibernate only checks this table matches the `Client` entity at startup — it never creates or alters it. Flyway owns the schema entirely.

## Database & Migrations

Flyway migrations live in `src/main/resources/db/migration` and run automatically on application startup, in order:

| Version | File | What it does |
|---|---|---|
| V1 | `V1__create_client_table.sql` | Creates the `client` table (see column list above) with the `balance_non_negative` check constraint. |
| V2 | `V2__insert_seed_clients.sql` | Seeds 4 sample rows into `client`. |
| V3 | `V3__update_seed_clients.sql` | Overwrites the first/last name and phone number of the 4 seeded rows (ids 1–4) with different sample data (`John Doe`, `Jane Roe`, `Richard Miles`, `Mary Major`) — balances from `V2` are untouched. |

**`V2` inserts, then `V3` overwrites names/phone numbers on top — net result after both run:**

| id | first_name | last_name | phone_number | balance |
|---|---|---|---|---|
| 1 | John | Doe | +12025550100 | 5000.00 |
| 2 | Jane | Roe | +12025550101 | 1200.00 |
| 3 | Richard | Miles | +12025550102 | 300.00 |
| 4 | Mary | Major | +12025550103 | 0.00 |

(`V2`'s original names — Anna Kowalska, Petro Shevchenko, Marek Nowak, Olha Melnyk — only exist transiently between the two migrations; a fresh database ends up at the table above.)

Beyond these 4 seeded rows, new clients can be added at runtime via `POST /clients` (see [API Reference](#api-reference)).

To reset the database from scratch locally:

```bash
psql -U postgres -c "DROP DATABASE IF EXISTS banking;"
psql -U postgres -c "CREATE DATABASE banking;"
./mvnw spring-boot:run   # Flyway runs V1, V2, then V3, on startup
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
- A running PostgreSQL server with a `banking` database, reachable with the settings in [Configuration](#configuration) (defaults: `localhost:5432`, user `postgres`, password `postgres`)
- No global Maven install required — use the bundled wrapper
- Docker is **not** needed to run the app, only to run the tests (see [Testing](#testing))

**Steps**

1. Create the database (if it doesn't already exist):
   ```bash
   createdb banking
   ```
2. If your local Postgres doesn't match the defaults, export the relevant `DB_*` variables instead of editing the file:
   ```bash
   export DB_USER=tsl DB_PASSWORD=secret
   ```
3. Start the app:
   ```bash
   ./mvnw spring-boot:run
   ```
   On Windows: `mvnw.cmd spring-boot:run`. Add `-Dspring-boot.run.profiles=dev` to see the SQL it runs.
4. The API is available at `http://localhost:8080`. Flyway applies any pending migrations (V1 table creation, V2 seed data, V3 seed-data overwrite) automatically before the app finishes starting.

**Building a runnable jar**

```bash
./mvnw clean package
java -jar target/banking-0.0.1-SNAPSHOT.jar
```

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

The whole transfer runs inside a single `@Transactional` service method, so if `withdraw` throws, the transaction rolls back and neither balance is modified.

## Error Handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) centralizes exception → HTTP status mapping for the whole app. Every handler returns a Spring `ProblemDetail`, so all errors are **RFC 7807 / RFC 9457 problem documents** served as `Content-Type: application/problem+json`:

| Exception | Status | `title` | `detail` |
|---|---|---|---|
| `ClientNotFoundException` | `404 Not Found` | `Client not found` | `Client not found: <id>` |
| `InsufficientFundsException` | `409 Conflict` | `Insufficient funds` | `Insufficient funds` |
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

## Testing

```bash
./mvnw test
```

**Requirements:** a running Docker daemon. You do *not* need a local PostgreSQL — `BankingApplicationTests` starts its own throwaway `postgres:16-alpine` container via Testcontainers, so `./mvnw test` is self-contained and safe to run against a machine with no `banking` database (and it never touches your local data).

Current state: **13 tests, all passing** (1 context test + 12 unit tests).

| Test class | Type | Coverage |
|---|---|---|
| `BankingApplicationTests` | Integration test (`@SpringBootTest` + `@Testcontainers`) | `contextLoads()` — boots the full application context against a disposable PostgreSQL container. Because startup runs Flyway and then `ddl-auto=validate`, this single test transitively proves that **V1→V3 apply cleanly to an empty database** and that the resulting schema **matches the `Client` entity**. |
| `service.ClientServiceTest` | Unit test (Mockito-mocked `ClientRepository`, no DB) | `getAllClients` (mapping, size); `getClientById` plus its `ClientNotFoundException` path; `updatePhoneNumber`; `updateLastName`; `updateClient`; `createClient` (returns the saved client's id and mapped fields); `transfer` — happy path plus same-account and insufficient-funds (`InsufficientFundsException`) cases. Balance assertions use AssertJ's `isEqualByComparingTo` (scale-independent `BigDecimal` comparison) rather than `isEqualTo`. |

Test methods follow a `method_whenCondition_expectedBehavior` naming convention (e.g. `transfer_whenInsufficientFunds_throwsInsufficientFunds`).

`withdraw_whenInsufficientFunds_throwsAndLeavesBalanceUnchanged` exercises the `Client` entity directly with no mocks — it constructs a `Client` and asserts the balance is untouched after a rejected withdrawal. It currently lives in `ClientServiceTest` despite testing the model rather than the service; a separate `ClientTest` would be the natural home as more domain behavior moves into the entity.

The negative- and zero-amount transfer tests were removed when validation moved to the DTOs: those inputs are now rejected by `@Positive` at the controller boundary, which a service-level unit test can't exercise. That check has effectively moved from a tested service guard to an **untested** annotation — nothing in the suite currently proves `@Valid` is wired up at all (see gaps below).

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

**Remaining gaps**

- `createClient`'s test only covers mapping a repository-returned `Client` to a `ClientResponse` — it doesn't assert *what* gets passed to `repository.save(...)` (e.g. that `id` is `null` going in).
- There is no `ClientController` test (no `@WebMvcTest` / MockMvc coverage), even though `spring-boot-starter-webmvc-test` is on the classpath. Controller routing, JSON (de)serialization, `201`/`Location` behavior on `POST /clients`, **every `@Valid` constraint**, and the `GlobalExceptionHandler` status mapping (including the new `404`/`409`) are not directly exercised. This is now the largest coverage gap, since validation is the layer that most recently absorbed business rules.
- **Nothing verifies that mutations are actually persisted.** Now that the service relies on dirty checking instead of explicit `save(...)` calls, the unit tests dropped their `verify(repository).save(...)` assertions and only assert that the in-memory entity was mutated. Against a mocked repository those assertions pass whether or not the change would ever reach the database — only a test running in a real transaction (e.g. `@DataJpaTest`) can prove the flush happens.
- `spring-boot-starter-data-jpa-test` was added to the POM but is currently unused — no `@DataJpaTest` slice test exists yet, so repository-layer behavior against a real database is untested. It is exactly what the gap above calls for.

The Maven Surefire plugin is configured with an explicit Mockito Java agent (`-javaagent:.../mockito-core-5.14.2.jar`) and `-Xshare:off`, required for Mockito's inline mock maker to work under recent JDKs.

## Build Tooling

- `./mvnw` / `mvnw.cmd` — Maven Wrapper, pinned to Maven 3.9.16 via `.mvn/wrapper/maven-wrapper.properties` (`wrapperVersion=3.3.4`, `distributionType=only-script`). No local Maven install is required.
- `.gitattributes` forces LF line endings for `mvnw` and CRLF for `*.cmd` files, so the wrapper scripts behave correctly regardless of the contributor's OS/git config.
- `pom.xml` declares a real `<name>` and `<description>`. The empty `<url>`, `<licenses>`, `<developers>`, and `<scm>` placeholders that Spring Initializr generates have been removed, so those elements are now **inherited** from `spring-boot-starter-parent` (Apache License 2.0, the Spring team, and Spring Boot's SCM URLs). That only surfaces in the effective POM (`./mvnw help:effective-pom`) and in published artifact metadata; re-add them as empty self-closing tags to suppress the inheritance. `HELP.md` still describes the original override pattern.
- `HELP.md` is boilerplate generated by Spring Initializr (links to Maven/Spring Boot docs) and isn't project-specific documentation.
- The Spring Boot Maven plugin excludes Lombok from the final packaged jar (it's a compile-time-only, `optional` dependency).

## Known Issues & Limitations

- **Validation gaps that remain**: Bean Validation now covers null/blank/sign, but not **string length** — `first_name`/`last_name` are `VARCHAR(100)` and `phone_number` is `VARCHAR(20)` with no matching `@Size`, so an over-long value still reaches Postgres and surfaces as an unhandled `DataIntegrityViolationException` (500). There is also no format check on `phoneNumber` (any non-blank string ≤20 chars is accepted).
- **`PATCH /clients/{id}/lastName` is unvalidated**: `LastNameRequest` has no constraints and the handler has no `@Valid`, so a blank or absent `lastName` silently blanks the stored value while every other endpoint rejects the equivalent input (see [API Reference](#patch-clientsidlastname)).
- **`type` is never set on problem documents**: every error leaves `type` at the default `about:blank` (so Spring omits it), meaning `title` is the only machine-readable discriminator between, say, a not-found and an insufficient-funds `409`. Assigning stable `type` URIs would let clients branch on an identifier rather than on display text.
- **`ClientRequest` serves two endpoints with different semantics**: `balance` seeds the opening balance on `POST /clients`, but is required-and-ignored on `PUT /clients/{id}` (see [API Reference](#put-clientsid)). Splitting it into `CreateClientRequest` / `UpdateClientRequest` would make both contracts honest.
- **Unused `java.math.BigDecimal` import in `ClientService`**: left behind when the `amount <= 0` guard and the balance arithmetic moved out; the class no longer references `BigDecimal`.
- **`updateClient_updatesAllFields` is now misnamed**: it no longer asserts anything about balance, because the endpoint no longer changes it — the name promises more than the test checks.
- **`createClient`'s test doesn't verify the saved entity**: it stubs `repository.save(any(Client.class))` and only checks the returned `ClientResponse`, so a bug that dropped a field before calling `save` (e.g. forgetting to copy `phoneNumber`) wouldn't be caught. There's also no controller-level test for `POST /clients` (see [Testing](#testing)).
- **No authentication/authorization**: every endpoint is unauthenticated and unauthorized — anyone who can reach port 8080 can read all client data and move money between any two accounts.
- **No repository- or controller-layer tests**: the Testcontainers setup proves the context boots and the migrations validate, but no test drives `ClientRepository` against the real database or the endpoints through MockMvc (see [Testing](#testing)).
- **`spring.jpa.open-in-view` is enabled by default**: Spring logs a warning about this on every startup. It keeps the Hibernate session open for the whole request, which can hide lazy-loading issues and hold DB connections longer than necessary; it's worth setting explicitly to `false`.
- **No API documentation tooling**: no OpenAPI/Swagger integration — this README is currently the only API reference.
- **No logging/observability beyond opt-in SQL logging**: the `dev` profile logs queries, but there's no structured application logging, metrics, or health-check endpoint (no Spring Boot Actuator dependency).
- **Credentials default to `postgres`/`postgres`**: `DB_USER`/`DB_PASSWORD` fall back to a well-known development credential pair. That's convenient locally, but any deployment that forgets to set them starts up with guessable credentials rather than failing fast — dropping the defaults (`${DB_PASSWORD}` with no fallback) would surface the misconfiguration at startup.
