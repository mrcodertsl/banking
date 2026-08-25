# Banking

A small Spring Boot REST API for managing bank clients and transferring money between accounts.

## Tech Stack

- Java 21
- Spring Boot 4.1.0 (Web MVC, Data JPA)
- PostgreSQL
- Flyway
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

Schema management uses [Flyway](https://flywaydb.org/) (`spring.jpa.hibernate.ddl-auto=validate` — Hibernate only validates the schema, it does not create or update it). Migration scripts live under `src/main/resources/db/migration`:

| Version | Change |
|---|---|
| V1 | Add `phone_number` column to `client` |
| V2 | Rename `client.name` to `client.first_name` |
| V3 | Add `last_name` column to `client` |

`spring.flyway.baseline-on-migrate=true` with `spring.flyway.baseline-version=0` means Flyway treats an existing `client` table as version 0 and applies V1+ on top of it.

> **Known issue:** there is no migration that creates the `client` table itself, so a brand-new empty database has nothing for V1–V3 to alter against. You need to create the base `client` table (columns: `id`, `name`, `balance`) yourself before starting the app, or point at a database where it already exists. There is currently no data seeder, so the table will be empty either way.

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
  { "id": 1, "firstName": "Anna", "lastName": "Smith", "balance": 5000.0 }
]
```

### `GET /clients/{id}`

Returns a single client by id.

**Response**

```json
{ "id": 1, "firstName": "Anna", "lastName": "Smith", "balance": 5000.0 }
```

### `PATCH /clients/{id}/phoneNumber`

Updates a client's phone number.

**Request body**

```json
{ "phoneNumber": "+10000000000" }
```

### `PATCH /clients/{id}/lastName`

Updates a client's last name.

**Request body**

```json
{ "lastName": "Smith" }
```

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

src/main/resources/db/migration  # Flyway migration scripts
```
