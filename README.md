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

Update the username/password to match your local PostgreSQL setup. The schema is managed automatically via `spring.jpa.hibernate.ddl-auto=update`.

On startup, `DataSeeder` populates the database with four sample clients if the `client` table is empty.

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
├── DataSeeder.java              # seeds sample clients on startup
├── controller/                  # REST controllers
├── dto/                         # request/response records
├── exceptions/                  # global exception handling
├── model/                       # JPA entities
├── repository/                  # Spring Data repositories
└── service/                     # business logic
```
