# Automated Security & Defensive Input Validation Suite

Automated defensive security and input-validation test suite for REST API endpoint:
```http
POST /test-login
Content-Type: application/x-www-form-urlencoded

bioId=<Long>&password=<String>
```

Built with **Java 17**, **JUnit 5**, and **REST Assured**.

---

## Table of Contents
1. [Overview & Security Assertions](#overview--security-assertions)
2. [How to Compile the Project](#how-to-compile-the-project)
3. [How to Run All Tests](#how-to-run-all-tests)
4. [How to Run a Particular Test or Category](#how-to-run-a-particular-test-or-category)
   - [Method 1: Via Maven Command Line](#method-1-via-maven-command-line)
   - [Method 2: Via IDE (IntelliJ IDEA / VS Code / Eclipse)](#method-2-via-ide-intellij-idea--vs-code--eclipse)
5. [Available Filter Keywords & Test Categories](#available-filter-keywords--test-categories)
6. [Complete List of Individual Test Methods](#complete-list-of-individual-test-methods)
7. [Configuring Target Server Base URL](#configuring-target-server-base-url)
8. [Reviewing Execution Logs (`security_test_results.log`)](#reviewing-execution-logs-security_test_resultslog)

---

## Overview & Security Assertions

Every test in this suite sends malicious or edge-case input to the endpoint and asserts:
- **Strict 4xx Status**: The API must respond with a `4xx` client error (e.g., `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `422 Unprocessable Entity`).
- **Never `200 OK`**: Prevents false acceptance or authentication bypass.
- **Never `5xx`**: Prevents unhandled server exceptions, crashes, or unhandled 500 errors.
- **No SQL Leaks**: Ensures response bodies never leak SQL syntax errors, database names, or table internals (`SQLException`, `syntax error at or near`, `hibernate`, `jdbc`, etc.).
- **No Stack Traces**: Ensures response bodies never expose Java/Spring exception traces (`NullPointerException`, `at org.springframework.`, `Whitelabel Error Page`, etc.).
- **No Command Injection Artifacts**: Ensures response bodies never contain shell execution artifacts (`uid=`, `root:`, `nt authority\system`, directory listings, etc.).
- **No Auth Bypass Leaks**: Ensures response bodies never return authentication tokens or success indicators.

---

## How to Compile the Project

Compile the source code and dependencies using Maven:

```powershell
mvn clean compile
```

Or standard compile:
```powershell
mvn compile
```

---

## How to Run All Tests

To execute all 112 automated security tests against the default endpoint (`http://localhost:8080/test-login`):

```powershell
mvn exec:java
```

---

## How to Run a Particular Test or Category

You can execute a specific test case or an entire test category in two ways:

### Method 1: Via Maven Command Line

You can pass the test name or category keyword directly using `-Dtest=...` (recommended, works cleanly in PowerShell & CMD) or `"-Dexec.args=..."`:

#### 1. Run by Category Name:
- Run only **SQL Injection** tests:
  ```powershell
  mvn exec:java -Dtest=sql
  ```
- Run only **XSS Injection** tests:
  ```powershell
  mvn exec:java -Dtest=xss
  ```
- Run only **OS Command Injection** tests:
  ```powershell
  mvn exec:java -Dtest=command
  ```
- Run only **Parameter Type & Missing Parameter** tests:
  ```powershell
  mvn exec:java -Dtest=param
  ```
- Run only **Boundary & Empty/Whitespace** tests:
  ```powershell
  mvn exec:java -Dtest=boundary
  ```
- Run only **Unicode & Emojis** tests:
  ```powershell
  mvn exec:java -Dtest=unicode
  ```
- Run only **Malformed JSON** tests:
  ```powershell
  mvn exec:java -Dtest=malformed
  ```
- Run only **Transport & Content-Type** tests:
  ```powershell
  mvn exec:java -Dtest=transport
  ```

#### 2. Run a Single Individual Test Method:
You can target any specific test method name directly:
- Run only the **Missing bioId** test:
  ```powershell
  mvn exec:java -Dtest=testMissingBioIdParameter
  ```
- Run only the **Missing Password** test:
  ```powershell
  mvn exec:java -Dtest=testMissingPasswordParameter
  ```
- Run only the **Empty Password** test:
  ```powershell
  mvn exec:java -Dtest=testEmptyPassword
  ```
- Run only the **64KB Buffer Limit** test:
  ```powershell
  mvn exec:java -Dtest=testOversizedPassword64KB
  ```
- Run only the **Parameter Tampering** test:
  ```powershell
  mvn exec:java -Dtest=testParameterTamperingExtraFields
  ```
- Run only the **Invalid bioId Types** test:
  ```powershell
  mvn exec:java -Dtest=testInvalidBioIdTypes
  ```

*(Note: In PowerShell, you can also use `"-Dexec.args=testMissingBioIdParameter"`)*

---

### Method 2: Via IDE (IntelliJ IDEA / VS Code / Eclipse)

1. Open [`FirstTest.java`](src/main/java/com/practice/FirstTest.java).
2. Look at the left gutter next to line numbers:
   - Next to each `@Test` or `@ParameterizedTest` method, you will see a green **Play ▶️** icon.
   - Click the green **Play ▶️** icon next to the specific test method you want to run.
   - Select **Run 'testMethodName()'**.
3. To run an entire category in the IDE:
   - Click the green **Play ▶️** icon next to any `@Nested class` (e.g., `SqlInjectionTests`).
   - Select **Run 'SqlInjectionTests'**.

---

## Available Filter Keywords & Test Categories

| Filter Keyword | Category Description | What It Tests |
| :--- | :--- | :--- |
| `sql` | SQL Injection | Boolean bypass (`' OR '1'='1`), stacked queries (`'; DROP TABLE users;--`), UNION queries, sleep blind injection, and SQLi in `bioId`. |
| `xss` | Cross-Site Scripting | `<script>alert(1)</script>`, `<img src=x onerror=...>`, `<svg onload=...>`, event handlers, iframes, and `bioId` XSS. |
| `command` or `cmd` | OS Command Injection | `; whoami`, `$(whoami)`, `& dir`, `\| id`, `; cat /etc/passwd`, `& calc.exe`, verifying input is never passed to a shell. |
| `boundary` | Boundary & Buffer Limits | Empty string `""`, whitespace only (`\t`, `\n`), 1,000 char, 10,000 char, and 64KB oversized payloads. |
| `unicode` | Encoding & Unicode | Emojis (`🔥🔒🚀👻🛡️`), non-Latin scripts (Japanese, Arabic, Cyrillic), null-byte injection (`admin\0pass`), and Right-to-Left (RLO) overrides. |
| `malformed` | Malformed & Objects | Malformed JSON strings (`{"admin": true}`), format strings (`%s%s%n`), path traversal (`../../etc/passwd`), and JNDI (`${jndi:ldap:...}`). |
| `param` or `type` | Parameter Types & Missing | Non-numeric `bioId` (`invalidBioId`, `abc123`), floats (`1234.5678`), integer overflow, missing `bioId`, missing `password`, and parameter tampering. |
| `transport` | Transport & Content Negotiation | Query parameters in POST (`/test-login?bioId=...&password=...`) and raw JSON payloads with `Content-Type: application/json`. |

---

## Complete List of Individual Test Methods

You can copy and run any of these method names with `-Dexec.args="<methodName>"`:

### 1. SQL Injection Tests (`SqlInjectionTests`)
- `testSqlInjectionInPassword` — Parameterized test covering 17 SQL injection vectors in `password`.
- `testSqlInjectionInBioId` — Parameterized test covering 5 SQL injection vectors in `bioId`.

### 2. XSS Tests (`XssInjectionTests`)
- `testXssInPassword` — Parameterized test covering 12 XSS payload strings in `password`.
- `testXssInBioId` — Parameterized test covering script tags in `bioId`.

### 3. OS Command Injection Tests (`CommandInjectionTests`)
- `testCommandInjectionInPassword` — Parameterized test covering 19 command execution strings in `password`.
- `testCommandInjectionInBioId` — Parameterized test covering command strings in `bioId`.

### 4. Boundary & Malformed Input Tests (`BoundaryAndMalformedInputTests`)
- `testEmptyPassword` — Tests empty string `""` password rejection.
- `testWhitespacePassword` — Tests whitespace-only (`" "`, `"\t"`, `"\n"`, `"\r\n"`) passwords.
- `testLongPassword1000Chars` — Tests 1,000 character password buffer limit.
- `testLongPassword10000Chars` — Tests 10,000 character password buffer limit.
- `testOversizedPassword64KB` — Tests 65,536 character (64KB) oversized payload.
- `testUnicodeAndSpecialCharsets` — Tests emojis, null-bytes, RTL, and non-ASCII character sets.
- `testMalformedJsonStrings` — Tests JSON and object injection payloads.
- `testFormatStringAndTraversalPayloads` — Tests `%s` format strings, path traversal, and `${jndi:...}` strings.

### 5. Parameter Types & Missing Parameters (`ParameterTypeAndMissingParameterTests`)
- `testInvalidBioIdTypes` — Tests alphanumeric, floating-point, NaN, and integer overflow in `bioId`.
- `testNegativeAndEmptyBioId` — Tests negative numbers (`-1`, `-999999`), zero, and empty `bioId`.
- `testMissingBioIdParameter` — Tests request with `bioId` omitted completely.
- `testMissingPasswordParameter` — Tests request with `password` omitted completely.
- `testMissingBothParameters` — Tests request with both `bioId` and `password` omitted.
- `testParameterTamperingExtraFields` — Tests unexpected admin privilege escalation parameters (`isAdmin=true`, `role=SUPERADMIN`).

### 6. Transport & Content-Type Tests (`TransportAndPayloadSecurityTests`)
- `testSqlInjectionViaQueryParams` — Tests SQL injection transmitted via query parameters on POST.
- `testJsonPayloadRejection` — Tests request with `application/json` payload body.

---

## Configuring Target Server Base URL

By default, tests send requests to:
`http://localhost:8080/test-login`

To point tests to another host, port, or base path, provide `-Dapi.base.url`:

```powershell
mvn exec:java -Dapi.base.url=http://localhost:8080/auth
```

Or combine it with a test filter:
```powershell
mvn exec:java -Dapi.base.url=http://localhost:8080/auth -Dexec.args="sql"
```

---

## Reviewing Execution Logs (`security_test_results.log`)

Execution logs are appended to [`security_test_results.log`](security_test_results.log).

Each log record includes:
1. **Timestamp & Category**
2. **Request Details**:
   - HTTP method and target URL
   - Exact request parameters sent (`bioId`, `password`, extra parameters)
3. **Response Details**:
   - HTTP Status Code (e.g., `HTTP 400`)
   - Response Time in milliseconds
   - Exact response body received from the server
4. **Security Verdict**:
   - `PASS` or detailed failure violation messages.

### Example Log Record:
```text
----------------------------------------------------------------------------------------
[2026-10-08 11:46:15.976] [PASS] Category: Missing Param | Test: password omitted from request
  [REQUEST]
    Endpoint & Transport: POST http://localhost:8080/test-login (application/x-www-form-urlencoded)
    Request Params Sent:
      * bioId          = 1001
      * password       = [OMITTED / NOT SENT]
  [RESPONSE]
    Status Code:          HTTP 400 (65 ms)
    Response Body:        {"message":"Missing parameter.","status":400}
  [VERDICT]
    Outcome:              PASS
----------------------------------------------------------------------------------------
```
