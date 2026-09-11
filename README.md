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
| Boilerplate reduction | Lombok |
| Build tool | Maven, via the included `./mvnw` / `mvnw.cmd` wrapper (Maven 3.9.16, wrapper 3.3.4 — see `.mvn/wrapper/maven-wrapper.properties`) |
| Test framework | JUnit 5, Mockito 5.14.2, AssertJ, `spring-boot-starter-webmvc-test` |

## Project Structure

```
src/main/java/com/roladio/banking
├── BankingApplication.java      # @SpringBootApplication entry point
├── controller/
│   └── ClientController.java    # REST endpoints, mapped under /clients
├── dto/
│   ├── ClientRequest.java       # PUT /clients/{id}/update body
│   ├── ClientResponse.java      # response shape for GET endpoints
│   ├── LastNameRequest.java     # PATCH .../lastName body
│   ├── PhoneNumberRequest.java  # PATCH .../phoneNumber body
│   └── TransferRequest.java     # POST /clients/transfer body
├── exceptions/
│   └── GlobalExceptionHandler.java  # @RestControllerAdvice mapping exceptions -> HTTP status
├── model/
│   └── Client.java               # JPA entity mapped to the `client` table
├── repository/
│   └── ClientRepository.java     # Spring Data JPA repository (JpaRepository<Client, Long>)
└── service/
    └── ClientService.java        # business logic: lookups, updates, transfers

src/main/resources/
├── application.properties        # datasource + JPA + logging config
└── db/migration/                 # Flyway migration scripts (see below)

src/test/java/com/roladio/banking
├── BankingApplicationTests.java          # Spring context smoke test
└── service/ClientServiceTest.java        # unit tests for ClientService
```

All DTOs are Java `record`s (immutable, no validation annotations — see [Known Issues](#known-issues--limitations)). All request/response bodies are plain JSON, there is no API versioning or content negotiation beyond the Spring Boot defaults.

`ClientController` is a thin layer: every method just delegates to `ClientService` and returns whatever it produces (or nothing, for the mutation endpoints, which currently return `void` / HTTP 200 with an empty body on success).

## Data Model

### `Client` entity (`model/Client.java`)

Lombok-generated `@Getter`/`@Setter`, an `@AllArgsConstructor`, and a `protected` no-args constructor (required by JPA, not meant to be called directly).

| Field | Java type | Notes |
|---|---|---|
| `id` | `Long` | `@Id`. **No `@GeneratedValue`** — Hibernate treats it as an assigned identifier. See [Known Issues](#known-issues--limitations). |
| `firstName` | `String` | |
| `lastName` | `String` | |
| `balance` | `double` | Primitive `double`, not `BigDecimal` — see [Known Issues](#known-issues--limitations). |
| `phoneNumber` | `String` | |

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
| V2 | `V2__insert_seed_clients.sql` | Seeds sample rows into `client`. |

**`V2` migration content (as of writing):**

```sql
INSERT INTO client (first_name, last_name, phone_number, balance)
VALUES ('Anna', 'Kowalska', '+48501234567', 5000.0),
       ('Petro', 'Shevchenko', '+380671112233', 1200.0),
       ('Marek', 'Nowak', '+48602987654', 300.0),
       ('Olha', 'Melnyk', '+380935556677', 0.0),
```

> ⚠️ **This file is currently broken.** The `VALUES` list ends on a trailing comma with no final row and no terminating `;`. Flyway will fail to apply it as-is against a fresh database. Fix it by either removing the trailing comma (to end with the `Olha Melnyk` row) or adding the missing row + semicolon before running the app against an empty schema.

Because there's no `V3+` yet, and no way to add clients through the API (see [Known Issues](#known-issues--limitations)), the only clients that will ever exist in a freshly-migrated database are the ones seeded by `V2`.

To reset the database from scratch locally:

```bash
psql -U tsl -d postgres -c "DROP DATABASE IF EXISTS banking;"
psql -U tsl -d postgres -c "CREATE DATABASE banking;"
./mvnw spring-boot:run   # Flyway runs V1, then (once fixed) V2, on startup
```

## Configuration

All configuration is in `src/main/resources/application.properties`:

```properties
spring.application.name=banking

spring.datasource.url=jdbc:postgresql://localhost:5432/banking
spring.datasource.username=tsl
spring.datasource.password=
spring.datasource.driver-class-name=org.postgresql.Driver

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```

| Property | Effect |
|---|---|
| `spring.datasource.url` / `username` / `password` | Connection to your local Postgres instance. Update these to match your setup — the password is currently blank. |
| `spring.jpa.hibernate.ddl-auto=validate` | Hibernate validates the entity ↔ table mapping at startup but never creates/alters schema. All schema changes must go through Flyway migrations. |
| `spring.jpa.show-sql` / `hibernate.format_sql` | Logs every SQL statement Hibernate executes, formatted, to stdout. Useful for debugging, noisy in production. |

There is no `application-test.properties` / test profile, and no per-environment config (dev/staging/prod) — the same file is used everywhere, including by `BankingApplicationTests` (see [Testing](#testing)).

## Running the App

**Prerequisites**

- JDK 21
- A running PostgreSQL server, reachable at `localhost:5432`, with a `banking` database and a user matching `application.properties` (default: user `tsl`, no password)
- No global Maven install required — use the bundled wrapper

**Steps**

1. Create the database (if it doesn't already exist):
   ```bash
   createdb banking
   ```
2. Update `spring.datasource.username` / `spring.datasource.password` in `application.properties` if your local Postgres role differs from the default.
3. Make sure `V2__insert_seed_clients.sql` is fixed (see [Database & Migrations](#database--migrations)) if you're running against a fresh database — otherwise Flyway migration will fail on startup and the app won't come up.
4. Start the app:
   ```bash
   ./mvnw spring-boot:run
   ```
   On Windows: `mvnw.cmd spring-boot:run`
5. The API is available at `http://localhost:8080`. Flyway applies any pending migrations automatically before the app finishes starting.

**Building a runnable jar**

```bash
./mvnw clean package
java -jar target/banking-0.0.1-SNAPSHOT.jar
```

## API Reference

Base path: `/clients`. No authentication, no pagination, no content negotiation beyond JSON.

### `GET /clients`

Returns every client.

```bash
curl http://localhost:8080/clients
```

**Response — `200 OK`**

```json
[
  { "id": 1, "firstName": "Anna", "lastName": "Kowalska", "balance": 5000.0 }
]
```

### `GET /clients/{id}`

Returns a single client.

```bash
curl http://localhost:8080/clients/1
```

**Response — `200 OK`**

```json
{ "id": 1, "firstName": "Anna", "lastName": "Kowalska", "balance": 5000.0 }
```

**Response — `400 Bad Request`** if `id` doesn't exist:

```json
{ "error": "Client not found: 1" }
```

### `PATCH /clients/{id}/phoneNumber`

Updates a client's phone number. Returns `200 OK` with an empty body on success.

```bash
curl -X PATCH http://localhost:8080/clients/1/phoneNumber \
  -H "Content-Type: application/json" \
  -d '{ "phoneNumber": "+10000000000" }'
```

### `PATCH /clients/{id}/lastName`

Updates a client's last name. Returns `200 OK` with an empty body on success.

```bash
curl -X PATCH http://localhost:8080/clients/1/lastName \
  -H "Content-Type: application/json" \
  -d '{ "lastName": "Smith" }'
```

### `PUT /clients/{id}/update`

Full replace of first name, last name, balance, and phone number in one call. Returns `200 OK` with an empty body on success.

```bash
curl -X PUT http://localhost:8080/clients/1/update \
  -H "Content-Type: application/json" \
  -d '{ "firstName": "Anna", "lastName": "Smith", "balance": 5000.0, "phoneNumber": "+10000000000" }'
```

Note: unlike `transfer`, this endpoint does not go through `@Transactional` save validation beyond what Hibernate's dirty-checking does within the transaction — the entity is mutated and flushed at commit.

### `POST /clients/transfer`

Moves `amount` from `fromId`'s balance to `toId`'s balance. Returns `200 OK` with an empty body on success.

```bash
curl -X POST http://localhost:8080/clients/transfer \
  -H "Content-Type: application/json" \
  -d '{ "fromId": 1, "toId": 2, "amount": 100.0 }'
```

**Validation & errors**

| Condition | Exception thrown | HTTP Status |
|---|---|---|
| `amount <= 0` | `IllegalArgumentException("Amount must be positive")` | 400 Bad Request |
| `fromId.equals(toId)` | `IllegalArgumentException("Cannot transfer to the same account")` | 400 Bad Request |
| Either client not found | `IllegalArgumentException("Client not found: <id>")` | 400 Bad Request |
| `from.balance < amount` | `IllegalStateException("Insufficient funds")` | 409 Conflict |

The whole transfer runs inside a single `@Transactional` service method — both balance updates are saved together, so a failure partway through rolls back both.

There is no endpoint to create a new client (`POST /clients` does not exist) — the only way rows enter the `client` table is the `V2` seed migration.

## Error Handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) centralizes exception → HTTP status mapping for the whole app:

| Exception | Status | Body |
|---|---|---|
| `IllegalArgumentException` | `400 Bad Request` | `{ "error": "<exception message>" }` |
| `IllegalStateException` | `409 Conflict` | `{ "error": "<exception message>" }` |

Any other unhandled exception (e.g. a database connectivity failure, a malformed JSON body) falls through to Spring Boot's default error handling and is **not** mapped to this `{ "error": ... }` shape.

## Testing

```bash
./mvnw test
```

⚠️ `BankingApplicationTests` is a plain `@SpringBootTest` with no embedded/in-memory database and no test profile — it boots the **full** application context against the datasource in `application.properties`. That means `./mvnw test` requires a real, reachable PostgreSQL instance with a migrated `banking` database, exactly like running the app itself. There is no H2/Testcontainers setup, so tests are not isolated from your local Postgres.

| Test class | Type | Coverage |
|---|---|---|
| `BankingApplicationTests` | Spring context smoke test | `contextLoads()` — verifies the application context starts. Requires a live DB connection. |
| `service.ClientServiceTest` | Unit test (Mockito-mocked `ClientRepository`, no DB) | `getAllClients` (mapping, size); `getClientById`; `updatePhoneNumber`; `updateLastName`; `updateClient`; `transfer` — happy path plus negative amount, zero amount, same-account, and insufficient-funds edge cases. |

There is currently no `ClientController` test (no `@WebMvcTest` / MockMvc coverage), even though `spring-boot-starter-webmvc-test` is on the test classpath. Controller routing, request/response (de)serialization, and the `GlobalExceptionHandler` mapping are exercised only indirectly (or not at all) by the existing tests.

The Maven Surefire plugin is configured with an explicit Mockito Java agent (`-javaagent:.../mockito-core-5.14.2.jar`) and `-Xshare:off`, required for Mockito's inline mock maker to work under recent JDKs.

## Build Tooling

- `./mvnw` / `mvnw.cmd` — Maven Wrapper, pinned to Maven 3.9.16 via `.mvn/wrapper/maven-wrapper.properties` (`wrapperVersion=3.3.4`, `distributionType=only-script`). No local Maven install is required.
- `.gitattributes` forces LF line endings for `mvnw` and CRLF for `*.cmd` files, so the wrapper scripts behave correctly regardless of the contributor's OS/git config.
- `pom.xml` has empty placeholder blocks for `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, and `<scm>` — these exist only to override (blank out) values inherited from the `spring-boot-starter-parent` POM, not because they're meant to be filled in (see the auto-generated `HELP.md` for Spring Initializr's explanation of this pattern).
- `HELP.md` is boilerplate generated by Spring Initializr (links to Maven/Spring Boot docs) and isn't project-specific documentation.
- The Spring Boot Maven plugin excludes Lombok from the final packaged jar (it's a compile-time-only, `optional` dependency).

## Known Issues & Limitations

- **Broken seed migration**: `V2__insert_seed_clients.sql` has an incomplete `INSERT` (trailing comma, no final row, no `;`). Flyway will fail to apply it against a fresh database. See [Database & Migrations](#database--migrations).
- **No client-creation endpoint**: there's no `POST /clients`. The only clients that can ever exist are the ones inserted by `V2`, plus whatever pre-existing rows a target database happens to have — there is no supported way to add new ones via the API.
- **`Client.id` has no `@GeneratedValue`**: the DB column is `GENERATED ALWAYS AS IDENTITY`, but the JPA entity treats `id` as an application-assigned identifier. This is currently masked because nothing ever persists a *new* `Client` through JPA (all mutation methods load an existing entity by id first) — but it means the entity and the schema disagree about who owns id generation, and any future "create client" feature would need this reconciled (either add `@GeneratedValue(strategy = GenerationType.IDENTITY)`, or explicitly use `OVERRIDING SYSTEM VALUE` inserts).
- **`balance` is a primitive `double`**: money represented as a binary floating-point type risks rounding/precision errors, despite the underlying column being an exact `NUMERIC(19,2)`. `BigDecimal` would be the safer choice.
- **No request validation**: none of the DTOs use Bean Validation (`@NotBlank`, `@Positive`, etc.) or `@Valid` on controller parameters. Blank/null names, negative balances via `PUT .../update` (bypassing the transfer-specific checks), and malformed phone numbers are all accepted by the API layer — the DB's `CHECK (balance >= 0)` constraint is the only backstop, and it would surface as a raw, unhandled `DataIntegrityViolationException` (500) rather than a clean 400.
- **Mutation endpoints return no representation**: `PATCH`/`PUT`/`POST /transfer` all return `void` (200 OK, empty body) rather than the updated resource.
- **No authentication/authorization**: every endpoint is unauthenticated and unauthorized — anyone who can reach port 8080 can read all client data and move money between any two accounts.
- **Tests require a live database**: see [Testing](#testing) — there's no embedded/in-memory DB or Testcontainers setup, so CI or a fresh clone can't run `./mvnw test` without first standing up and migrating a real Postgres instance.
- **No API documentation tooling**: no OpenAPI/Swagger integration — this README is currently the only API reference.
- **No logging/observability beyond SQL logging**: `spring.jpa.show-sql=true` logs queries, but there's no structured application logging, metrics, or health-check endpoint (no Spring Boot Actuator dependency).
