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
| Test framework | JUnit 5, Mockito 5.14.2, AssertJ (`spring-boot-starter-webmvc-test`) |
| Integration testing | Testcontainers 2.0.5 (`spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`) — spins up a real PostgreSQL in Docker for the context test |

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
├── BankingApplicationTests.java          # context test, backed by a Testcontainers PostgreSQL
└── service/ClientServiceTest.java        # unit tests for ClientService (Mockito, no DB)
```

All DTOs are Java `record`s (immutable, no validation annotations — see [Known Issues](#known-issues--limitations)). All request/response bodies are plain JSON, there is no API versioning or content negotiation beyond the Spring Boot defaults.

`ClientController` is a thin layer: every method just delegates to `ClientService` and returns whatever it produces — `void` (HTTP 200, empty body) for `PATCH`/`PUT`/`POST /transfer`, and a `ClientResponse` for the `GET` endpoints and `POST /clients` (create).

## Data Model

### `Client` entity (`model/Client.java`)

Lombok-generated `@Getter`/`@Setter`, an `@AllArgsConstructor`, and a `protected` no-args constructor (required by JPA, not meant to be called directly).

| Field | Java type | Notes |
|---|---|---|
| `id` | `Long` | `@Id` with `@GeneratedValue(strategy = GenerationType.IDENTITY)` — matches the DB's `GENERATED ALWAYS AS IDENTITY` column. |
| `firstName` | `String` | |
| `lastName` | `String` | |
| `balance` | `BigDecimal` | Exact decimal arithmetic, matching the `NUMERIC(19,2)` column. Comparisons/arithmetic use `compareTo`/`add`/`subtract` (not `==`/`+`/`-`) throughout `ClientService`. |
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
psql -U tsl -d postgres -c "DROP DATABASE IF EXISTS banking;"
psql -U tsl -d postgres -c "CREATE DATABASE banking;"
./mvnw spring-boot:run   # Flyway runs V1, V2, then V3, on startup
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

There is no `application-test.properties` / test profile and no per-environment config (dev/staging/prod), so this single file applies everywhere — **except** for the datasource during tests: `BankingApplicationTests` uses Testcontainers' `@ServiceConnection`, which overrides `spring.datasource.*` at runtime to point at a throwaway PostgreSQL container instead of `localhost:5432/banking` (see [Testing](#testing)). Everything else here — `ddl-auto=validate`, SQL logging, Flyway defaults — still applies to the test context.

## Running the App

**Prerequisites**

- JDK 21
- A running PostgreSQL server, reachable at `localhost:5432`, with a `banking` database and a user matching `application.properties` (default: user `tsl`, no password)
- No global Maven install required — use the bundled wrapper
- Docker is **not** needed to run the app, only to run the tests (see [Testing](#testing))

**Steps**

1. Create the database (if it doesn't already exist):
   ```bash
   createdb banking
   ```
2. Update `spring.datasource.username` / `spring.datasource.password` in `application.properties` if your local Postgres role differs from the default.
3. Start the app:
   ```bash
   ./mvnw spring-boot:run
   ```
   On Windows: `mvnw.cmd spring-boot:run`
4. The API is available at `http://localhost:8080`. Flyway applies any pending migrations (V1 table creation, V2 seed data, V3 seed-data overwrite) automatically before the app finishes starting.

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

**Response — `400 Bad Request`** if `id` doesn't exist:

```json
{ "error": "Client not found: 1" }
```

### `POST /clients`

Creates a new client. `id` is DB-generated (`IDENTITY`) — do not include it in the request.

```bash
curl -i -X POST http://localhost:8080/clients \
  -H "Content-Type: application/json" \
  -d '{ "firstName": "Nadia", "lastName": "Petrenko", "balance": 250.00, "phoneNumber": "+380501234567" }'
```

**Response — `201 Created`**, with a `Location: /clients/{id}` header pointing at the new resource:

```json
{ "id": 5, "firstName": "Nadia", "lastName": "Petrenko", "balance": 250.00 }
```

Note there's no uniqueness or required-field check beyond the DB's own `first_name NOT NULL` and `balance >= 0` constraints — violating either surfaces as a raw, unhandled `DataIntegrityViolationException` (`500`), not a clean `400` (see [Known Issues](#known-issues--limitations)).

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
  -d '{ "firstName": "Anna", "lastName": "Smith", "balance": 5000.00, "phoneNumber": "+10000000000" }'
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

**Requirements:** a running Docker daemon. You do *not* need a local PostgreSQL — `BankingApplicationTests` starts its own throwaway `postgres:16-alpine` container via Testcontainers, so `./mvnw test` is self-contained and safe to run against a machine with no `banking` database (and it never touches your local data).

Current state: **13 tests, all passing** (1 context test + 12 unit tests).

| Test class | Type | Coverage |
|---|---|---|
| `BankingApplicationTests` | Integration test (`@SpringBootTest` + `@Testcontainers`) | `contextLoads()` — boots the full application context against a disposable PostgreSQL container. Because startup runs Flyway and then `ddl-auto=validate`, this single test transitively proves that **V1→V3 apply cleanly to an empty database** and that the resulting schema **matches the `Client` entity**. |
| `service.ClientServiceTest` | Unit test (Mockito-mocked `ClientRepository`, no DB) | `getAllClients` (mapping, size); `getClientById`; `updatePhoneNumber`; `updateLastName`; `updateClient`; `createClient` (returns the saved client's id and mapped fields); `transfer` — happy path plus negative amount, zero amount, same-account, and insufficient-funds edge cases. Balance assertions use AssertJ's `isEqualByComparingTo` (scale-independent `BigDecimal` comparison) rather than `isEqualTo`. |

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
- There is no `ClientController` test (no `@WebMvcTest` / MockMvc coverage), even though `spring-boot-starter-webmvc-test` is on the classpath. Controller routing, JSON (de)serialization, `201`/`Location` behavior on `POST /clients`, and the `GlobalExceptionHandler` status mapping are not directly exercised.
- `spring-boot-starter-data-jpa-test` was added to the POM but is currently unused — no `@DataJpaTest` slice test exists yet, so repository-layer behavior against a real database is untested.

The Maven Surefire plugin is configured with an explicit Mockito Java agent (`-javaagent:.../mockito-core-5.14.2.jar`) and `-Xshare:off`, required for Mockito's inline mock maker to work under recent JDKs.

## Build Tooling

- `./mvnw` / `mvnw.cmd` — Maven Wrapper, pinned to Maven 3.9.16 via `.mvn/wrapper/maven-wrapper.properties` (`wrapperVersion=3.3.4`, `distributionType=only-script`). No local Maven install is required.
- `.gitattributes` forces LF line endings for `mvnw` and CRLF for `*.cmd` files, so the wrapper scripts behave correctly regardless of the contributor's OS/git config.
- `pom.xml` has empty placeholder blocks for `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, and `<scm>` — these exist only to override (blank out) values inherited from the `spring-boot-starter-parent` POM, not because they're meant to be filled in (see the auto-generated `HELP.md` for Spring Initializr's explanation of this pattern).
- `HELP.md` is boilerplate generated by Spring Initializr (links to Maven/Spring Boot docs) and isn't project-specific documentation.
- The Spring Boot Maven plugin excludes Lombok from the final packaged jar (it's a compile-time-only, `optional` dependency).

## Known Issues & Limitations

- **No request validation**: none of the DTOs use Bean Validation (`@NotBlank`, `@Positive`, etc.) or `@Valid` on controller parameters. Blank/null names, negative balances via `PUT .../update` or `POST /clients` (bypassing the transfer-specific checks), and malformed phone numbers are all accepted by the API layer — the DB's `NOT NULL`/`CHECK (balance >= 0)` constraints are the only backstop, and violating them surfaces as a raw, unhandled `DataIntegrityViolationException` (500) rather than a clean 400.
- **`createClient`'s test doesn't verify the saved entity**: it stubs `repository.save(any(Client.class))` and only checks the returned `ClientResponse`, so a bug that dropped a field before calling `save` (e.g. forgetting to copy `phoneNumber`) wouldn't be caught. There's also no controller-level test for `POST /clients` (see [Testing](#testing)).
- **Mutation endpoints mostly return no representation**: `PATCH`/`PUT`/`POST /transfer` all return `void` (200 OK, empty body) rather than the updated resource — only the new `POST /clients` returns the created representation (`201` + body + `Location` header).
- **No authentication/authorization**: every endpoint is unauthenticated and unauthorized — anyone who can reach port 8080 can read all client data and move money between any two accounts.
- **No repository- or controller-layer tests**: the Testcontainers setup proves the context boots and the migrations validate, but no test drives `ClientRepository` against the real database or the endpoints through MockMvc (see [Testing](#testing)).
- **`spring.jpa.open-in-view` is enabled by default**: Spring logs a warning about this on every startup. It keeps the Hibernate session open for the whole request, which can hide lazy-loading issues and hold DB connections longer than necessary; it's worth setting explicitly to `false`.
- **No API documentation tooling**: no OpenAPI/Swagger integration — this README is currently the only API reference.
- **No logging/observability beyond SQL logging**: `spring.jpa.show-sql=true` logs queries, but there's no structured application logging, metrics, or health-check endpoint (no Spring Boot Actuator dependency).
