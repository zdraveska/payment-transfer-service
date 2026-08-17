# Payment Transfer Service

A basic payment transfer service for a digital banking platform. It supports transfers between accounts, 
balance validation, error handling, idempotency and transaction recording.

## Tech Stack

* Java 25, Spring Boot 4.1
* Spring Web MVC, Data JPA, Bean Validation
* Gradle (wrapper included)
* H2 for local runs, PostgreSQL for Docker/integration tests
* MapStruct and Lombok
* JUnit 5, Mockito, AssertJ, Testcontainers
* springdoc-openapi (Swagger UI)

## Running Locally

Start the application with:

```bash
./gradlew bootRun
```

The application starts on `http://localhost:8080` and seeds two demo accounts. Their account IDs and balances are printed in the startup log:

```text
Seeded demo data: Jane's account=<uuid> (balance 1000.00 EUR), John's account=<uuid> (balance 500.00 EUR)
```

Use those IDs in the API examples below.

### Swagger UI

`http://localhost:8080/swagger-ui/index.html`

### H2 Console

`http://localhost:8080/h2-console`

JDBC URL: `jdbc:h2:mem:bankdb`
User: `sa`
Password:

## Running with Docker

```bash
docker compose up --build
```

This runs the application against PostgreSQL using the `docker` Spring profile.

## API

| Method | Path                                        | Description                   |
| ------ | ------------------------------------------- | ----------------------------- |
| POST   | `/api/v1/payment-transfers/initiate`        | Initiate a transfer           |
| GET    | `/api/v1/payment-transfers/{transactionId}` | Get a transaction             |
| GET    | `/api/v1/accounts/{accountId}/transactions` | Get an account's transactions |

### Initiate a Transfer

```bash
curl -X POST http://localhost:8080/api/v1/payment-transfers/initiate \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "<jane-account-id>",
    "destinationAccountId": "<john-account-id>",
    "amount": 100.00,
    "currency": "EUR",
    "idempotencyKey": "<unique-key>"
  }'
```

Response for a successful transfer:

```json
{
  "transactionId": "...",
  "sourceAccountId": "...",
  "destinationAccountId": "...",
  "amount": 100.00,
  "currency": "EUR",
  "status": "SUCCESS",
  "timestamp": "..."
}
```

### Fetch a Transaction

```bash
curl -X 'GET' \
  'http://localhost:8080/api/v1/payment-transfers/eb6da61e-38f0-466b-94a6-0d39710a7f5d' \
  -H 'accept: */*'
```
Response for a failed transfer:

```json
{
  "transactionId": "...",
  "sourceAccountId": "...",
  "destinationAccountId": "...",
  "amount": 15000,
  "currency": "EUR",
  "status": "FAILED",
  "failureReason": "Transfer amount 15000 exceeds the maximum allowed per-transfer limit of 10000",
  "timestamp": "..."
}
```

## Error Handling

Errors use a consistent response format:

```json
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "Insufficient funds for account <account_id>: balance 10, attempted transfer amount 100",
  "timestamp": "..."
}
```
The `Recorded` column shows whether the attempt leaves a transaction row. Recorded attempts are stored with status 
`FAILED` and a `failureReason` and are returned by `GET /api/v1/accounts/{accountId}/transactions` alongside successful ones.

| Condition                           | Status | Code                       | Recorded        |
  | ----------------------------------- | -----: | -------------------------- |-----------------|
| Invalid request                     |    400 | `VALIDATION_ERROR`         | No              |
| Malformed request body              |    400 | `MALFORMED_REQUEST`        | No              |
| Same source and destination account |    400 | `INVALID_TRANSFER`         | No              |
| Account not found                   |    404 | `ACCOUNT_NOT_FOUND`        | No              |
| Idempotency key conflict            |    409 | `IDEMPOTENCY_KEY_CONFLICT` | No              |
| Conflicting concurrent request      |    409 | `DATA_INTEGRITY_VIOLATION` | No              |
| Transaction not found               |    404 | `TRANSACTION_NOT_FOUND`    | read-only       |
| Currency mismatch                   |    400 | `INVALID_TRANSFER`         | Yes — `FAILED`  |
| Insufficient funds                  |    422 | `INSUFFICIENT_FUNDS`       | Yes — `FAILED`  |
| Transfer exceeds configured limit   |    422 | `TRANSFER_LIMIT_EXCEEDED`  | Yes — `FAILED`  |
| Unexpected server error             |    500 | `INTERNAL_ERROR`           | Yes — `PENDING` |

The dividing line is whether both accounts were successfully resolved and locked. Everything rejected before that point 
cannot be recorded because a transaction row requires foreign keys to two existing accounts.

`INTERNAL_ERROR` is the only case that leaves a `PENDING` row rather than a `FAILED` one.
The transfer itself is rolled back and no money moves, but the transaction row was committed separately before the failure. 
Handling these rows would require a cleaner, which is a good point of improvement
(e.g., a scheduled job that retries or marks them `FAILED` after a timeout).

## Tests

Run the test suite with:

```bash
./gradlew test
```

The tests include:

* Unit tests for transfer logic and error handling.
* HTTP integration tests using PostgreSQL and Testcontainers.
* Concurrent transfers to verify balance consistency.
* Concurrent requests using the same idempotency key.
* Verification that failed transfer attempts are recorded.

Docker must be running for the Testcontainers integration tests.

## Implementation Notes

* Transfers update both account balances in a single database transaction.
* Both account rows are locked during a transfer to prevent concurrent transfers from overdrawing an account.
* Accounts are locked in a consistent order to avoid deadlocks between transfers in opposite directions.
* A database uniqueness constraint provides idempotency protection against duplicate requests.
* Successful transfers are recorded, as are transfers rejected after both accounts were resolved.
* A failed transfer releases its idempotency key so the client can retry with the same key.
  The failed attempt is still recorded, but without the key, since the key's uniqueness constraint is what enforces idempotency.
* A configurable per-transfer limit is enabled by default at 10,000.

## Scope and Limitations

Due to this being an assignment implementation, the service has several limitations and is not intended for production use.

* No authentication or authorization.
* No account-management API; demo accounts are created at startup.
* No account status such as frozen or closed.
* H2 console is intended for local development only.
