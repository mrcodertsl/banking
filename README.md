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
│   └── TransferRequest.java     # POST /clients/transfer body
├── exceptions/
│   ├── ClientNotFoundException.java     # unchecked; carries "Client not found: <id>" -> 404
│   ├── InsufficientFundsException.java  # unchecked; carries "Insufficient funds" -> 409
│   └── GlobalExceptionHandler.java      # @RestControllerAdvice mapping exceptions -> HTTP status
├── model/
│   └── Client.java               # JPA entity mapped to the `client` table
├── repository/
│   └── ClientRepository.java     # JpaRepository<Client, Long> + a row-locking lookup
└── service/
    └── ClientService.java        # business logic: lookups, updates, transfers

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

All DTOs are Java `record`s (immutable, no validation annotations — see [Known Issues](#known-issues--limitations)). All request/response bodies are plain JSON, there is no API versioning or content negotiation beyond the Spring Boot defaults.

`ClientController` is a thin layer: every method just delegates to `ClientService`. The mutating endpoints (`PATCH`/`PUT`/`POST /transfer`) are `void` and annotated `@ResponseStatus(HttpStatus.NO_CONTENT)`, so they answer `204`; the `GET` endpoints return a `ClientResponse` with `200`, and `POST /clients` builds its own `201` response.

`ClientService` maps entities to DTOs through a single private `toResponse(Client)` helper, and persists through **JPA dirty checking** rather than explicit saves: every mutating method is `@Transactional` and loads its entity through the repository (`findById`, or the locking `findByIdForUpdate` in `transfer` — see [Concurrency](#concurrency)), so the entity is managed and Hibernate flushes the changes at commit. Only `createClient` calls `clientRepository.save(...)`, because a brand-new entity has to be made managed first. This means the absence of a `save(...)` call in `updatePhoneNumber`, `updateLastName`, `updateClient`, and `transfer` is deliberate, not an oversight.

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

With Docker Compose, delete the data volume and start over:

```bash
docker compose down -v && docker compose up -d
./mvnw spring-boot:run   # Flyway runs V1, V2, then V3, on startup
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
4. The API is available at `http://localhost:8080`. Flyway applies any pending migrations (V1 table creation, V2 seed data, V3 seed-data overwrite) automatically before the app finishes starting.

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

The emitted document is **OpenAPI 3.1.0** and covers all seven operations and all five DTO schemas. `/swagger-ui.html` is a convenience path — it answers `302` and redirects to `/swagger-ui/index.html`, which is where the UI is actually served.

> ⚠️ **The generated spec is an explorer, not the full contract.** Three things it does not capture, so this README remains authoritative:
>
> - **`POST /clients` is documented as `200`, but really returns `201`.** The status is built at runtime by `ResponseEntity.created(...)`, which springdoc cannot infer statically. The four `@ResponseStatus(NO_CONTENT)` endpoints *are* reported correctly as `204`.
> - **No error responses are described at all** — none of the `400`, `404` or `409` outcomes, nor the RFC 7807 body they carry (see [Error Handling](#error-handling)).
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

The whole transfer runs inside a single `@Transactional` service method, so if `withdraw` throws, the transaction rolls back and neither balance is modified. Both accounts are also row-locked for the duration of the transaction, in a fixed order that rules out deadlocks — see [Concurrency](#concurrency).

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

## Concurrency

Only `transfer` needs concurrency control, and it is the one operation that gets it.

**The problem.** A transfer is a read-modify-write on two rows. Two transfers touching the same account concurrently could each read a balance of 5000, each subtract 100, and each write 4900 — one debit silently lost. Because balances are mutated through JPA dirty checking rather than an atomic `UPDATE … SET balance = balance - ?`, nothing in the database prevents that interleaving on its own.

**The fix — lock the rows before reading them.** `ClientRepository` exposes a locking lookup:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Client c where c.id = :id")
Optional<Client> findByIdForUpdate(@Param("id") Long id);
```

`transfer` uses this instead of the plain `findById`, so each account row is locked for the duration of the transaction and a competing transfer blocks until the first commits. The explicit `@Query` is required: Spring Data cannot derive a query from the name `findByIdForUpdate`, since it would try to read `IdForUpdate` as a property path.

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

Current state: **19 tests, all passing** (12 unit, 4 controller-slice, 2 integration, 1 context), in about 13 seconds.

| Test class | Type | Coverage |
|---|---|---|
| `BankingApplicationTests` | Integration (`@SpringBootTest` + `@Testcontainers`) | `contextLoads()` — boots the full application context against a disposable PostgreSQL container. Because startup runs Flyway and then `ddl-auto=validate`, this single test transitively proves that **V1→V3 apply cleanly to an empty database** and that the resulting schema **matches the `Client` entity**. |
| `service.ClientServiceTest` | Unit (Mockito-mocked `ClientRepository`, no DB) | `getAllClients` (mapping, size); `getClientById` plus its `ClientNotFoundException` path; `updatePhoneNumber`; `updateLastName`; `updateClient`; `createClient` (returns the saved client's id and mapped fields); `transfer` — happy path plus same-account and insufficient-funds cases. Balance assertions use AssertJ's `isEqualByComparingTo` (scale-independent `BigDecimal` comparison) rather than `isEqualTo`. |
| `service.ClientServiceIntegrationTest` | Integration (`@SpringBootTest` + `@Testcontainers`) | Drives the real `ClientService` against a real database: a transfer moves money and **both balances survive the commit**, and a failed transfer (insufficient funds) **leaves both balances unchanged**, proving the rollback. |
| `controller.ClientControllerTest` | Web slice (`@WebMvcTest(ClientController.class)` + `@MockitoBean` service) | Exercises the HTTP layer with MockMvc and no database: `404` + problem-document `status`/`detail` for an unknown id; `400` with `errors.amount` for a negative amount; `400` with `errors.{fromId,toId,amount}` for an empty body; `409` when the service reports insufficient funds. |

Test methods follow a `method_whenCondition_expectedBehavior` naming convention (e.g. `transfer_whenInsufficientFunds_throwsInsufficientFunds`).

`withdraw_whenInsufficientFunds_throwsAndLeavesBalanceUnchanged` exercises the `Client` entity directly with no mocks — it constructs a `Client` and asserts the balance is untouched after a rejected withdrawal. It currently lives in `ClientServiceTest` despite testing the model rather than the service; a separate `ClientTest` would be the natural home as more domain behavior moves into the entity.

The negative- and zero-amount transfer tests were removed from `ClientServiceTest` when validation moved to the DTOs: those inputs are rejected by `@Positive` at the controller boundary, which a service-level unit test can't reach. `ClientControllerTest` now covers that boundary instead — `transfer_whenAmountIsNegative_returns400` is the test that proves `@Valid` is actually wired up, and it asserts on `$.errors.amount`, so it also pins the problem-document shape described in [Error Handling](#error-handling).

**Two deliberate choices in `ClientServiceIntegrationTest` are worth preserving:**

- **It is not `@Transactional`.** Annotating the test class would wrap each test in a transaction that rolls back at the end — which would silently destroy the very thing these tests exist to prove. Because they commit for real, they demonstrate that dirty checking actually flushes.
- **It reads balances before acting** rather than asserting absolute amounts. The tests share a database with each other, and the money-moving test commits, so hard-coding "5000.00" would make them order-dependent. Capturing `fromBefore`/`toBefore` and asserting a *delta* keeps them independent.

They do depend on the Flyway seed data, though: ids 1, 2 and 4 must exist, and **client 4 must have a zero balance** for the insufficient-funds case (`V2` seeds it at `0.0`; `V3` renames it to Mary Major without touching the balance). Changing the seed migrations can break these tests — see [Database & Migrations](#database--migrations).

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

`verify` runs the full lifecycle up to and including packaging, so CI compiles, executes all 19 tests, builds the jar, and repackages it as a Spring Boot executable archive — a stricter gate than `test` alone.

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
- **No authentication/authorization**: every endpoint is unauthenticated and unauthorized — anyone who can reach port 8080 can read all client data and move money between any two accounts.
- **`spring.jpa.open-in-view` is enabled by default**: Spring logs a warning about this on every startup. It keeps the Hibernate session open for the whole request, which can hide lazy-loading issues and hold DB connections longer than necessary; it's worth setting explicitly to `false`.
- **The generated OpenAPI spec is incomplete**: springdoc now publishes Swagger UI and an OpenAPI 3.1 document, but it reports `200` for `POST /clients` (actually `201`), describes no error responses, and drops the `@Positive`/`@PositiveOrZero` bounds — see [API Documentation](#api-documentation). Until `@ApiResponse`/`@Operation` annotations are added, the spec cannot be used as the contract on its own.
- **No logging/observability beyond opt-in SQL logging**: the `dev` profile logs queries, but there's no structured application logging, metrics, or health-check endpoint (no Spring Boot Actuator dependency).
- **Credentials default to `postgres`/`postgres`**: `DB_USER`/`DB_PASSWORD` fall back to a well-known development credential pair. That's convenient locally, but any deployment that forgets to set them starts up with guessable credentials rather than failing fast — dropping the defaults (`${DB_PASSWORD}` with no fallback) would surface the misconfiguration at startup.
