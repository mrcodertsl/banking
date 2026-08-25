# Banking

A small Spring Boot REST API for managing bank clients and transferring money between accounts.

## Tech Stack

- Java 21
- Spring Boot 4.1.0 (Web MVC, Data JPA)
- PostgreSQL
- Lombok
- Maven

## Prerequisites

- JDK 21
- PostgreSQL running locally with a `banking` database
- Maven (or use the included `./mvnw` wrapper)

## Configuration

Database connection settings are in `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/banking
spring.datasource.username=tsl
spring.datasource.password=
```

Update the username/password to match your local PostgreSQL setup.

Schema management uses [Flyway](https://flywaydb.org/) (`spring.jpa.hibernate.ddl-auto=validate` — Hibernate only validates the schema, it does not create or update it).

> **Known issue:** no Flyway migration scripts exist yet under `src/main/resources/db/migration`, so the app currently has no schema to validate against and will fail to start until migrations are added. The previous `DataSeeder` (which populated sample clients on startup) has also been removed and not yet replaced.

## Running the app

```bash
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`.

## Running tests

```bash
./mvnw test
```

## API

### `GET /clients`

Returns all clients.

**Response**

```json
[
  { "id": 1, "name": "Anna", "balance": 5000.0 }
]
```

### `GET /clients/{id}`

Returns a single client by id.

**Response**

```json
{ "id": 1, "name": "Anna", "balance": 5000.0 }
```

> **Known issue:** the route is currently declared as `@GetMapping("/{id}}")` (note the extra `}`) in `ClientController`, so it will not match `/clients/{id}` as intended until fixed.

### `POST /clients/transfer`

Transfers an amount from one client's balance to another.

**Request body**

```json
{ "fromId": 1, "toId": 2, "amount": 100.0 }
```

**Errors**

| Condition | Status |
|---|---|
| Amount not positive | 400 Bad Request |
| `fromId` equals `toId` | 400 Bad Request |
| Client not found | 400 Bad Request |
| Insufficient funds | 409 Conflict |

## Project structure

```
src/main/java/com/roladio/banking
├── BankingApplication.java     # entry point
├── controller/                  # REST controllers
├── dto/                         # request/response records
├── exceptions/                  # global exception handling
├── model/                       # JPA entities
├── repository/                  # Spring Data repositories
└── service/                     # business logic
```
