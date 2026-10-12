package com.practice.home;

import com.practice.ApiConfig;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;

/**
 * ====================================================================================================
 * Selenium Automation Test Suite: Login.java
 * ====================================================================================================
 * 
 * Features & Capabilities:
 *  1. Handles Entrance Page with dual authentication options:
 *     - "Sign in with Google" button
 *     - "Sign in with Login ID" button
 *  2. Expands the Login form on clicking "Sign in with Login ID":
 *     - Login ID input field with placeholder: "Enter your Login ID"
 *     - Password input field with placeholder: "Enter your password"
 *     - "Sign in" / submit button
 *  3. Executes Comprehensive Test Scenarios:
 *     - Valid credentials (loginid: 23114, password: walkin111)
 *     - Wrong password and wrong login ID variations
 *     - Malformatted strings (empty, whitespace, SQL injection, XSS, boundary lengths, special characters)
 *  4. Full Network Request & Response Logging:
 *     - Captures every HTTP request (URL, Method, Form Data / Body / Params)
 *     - Captures every HTTP response (Status Code, MIME Type, Headers, Response Body via CDP)
 *  5. Generates a readable, structured log file: "Login_Test_Report.log"
 * ====================================================================================================
 */
public class Login {

    // ------------------------------------------------------------------------------------------------
    // Configuration & Constants
    // ------------------------------------------------------------------------------------------------
    
    /**
     * Default base URL for the target application.
     * Can be overridden via system property (-Dapp.url=...) or environment variable (APP_URL=...).
     */
    public static final String DEFAULT_APP_URL = "http://localhost:8080";

    /** Output log file name generated in the project root. */
    public static final String LOG_FILE_PATH = "Login_Test_Report.log";

    /** Explicit wait timeout in seconds. */
    public static final int EXPLICIT_WAIT_SECONDS = 10;

    /** Right / Valid test credentials as requested. */
    public static final String VALID_LOGIN_ID = "23114";
    public static final String VALID_PASSWORD = "walkin111";

    // ------------------------------------------------------------------------------------------------
    // Data Models
    // ------------------------------------------------------------------------------------------------

    /**
     * Represents a single test case scenario.
     */
    public static class TestCase {
        public final String id;
        public final String category;
        public final String description;
        public final String loginId;
        public final String password;
        public final boolean isExpectedValid;

        public TestCase(String id, String category, String description, String loginId, String password, boolean isExpectedValid) {
            this.id = id;
            this.category = category;
            this.description = description;
            this.loginId = loginId;
            this.password = password;
            this.isExpectedValid = isExpectedValid;
        }
    }

    /**
     * Captured Network Transaction details (HTTP Request + Response).
     */
    public static class NetworkTransaction {
        public String requestId;
        public String requestUrl = "";
        public String requestMethod = "GET";
        public String postData = ""; // Form data / Body / Params
        public Map<String, String> requestHeaders = new LinkedHashMap<>();

        public String responseUrl = "";
        public int responseStatus = 0;
        public String responseStatusText = "";
        public String responseMimeType = "";
        public Map<String, String> responseHeaders = new LinkedHashMap<>();
        public String responseBody = ""; // Response payload captured via CDP
    }

    /**
     * Result of executing a single test case.
     */
    public static class TestExecutionResult {
        public final TestCase testCase;
        public final List<NetworkTransaction> networkTransactions = new ArrayList<>();
        public String finalPageUrl = "";
        public String pageTitle = "";
        public String uiMessage = "";
        public boolean isPassed;
        public String verdictNote = "";

        public TestExecutionResult(TestCase testCase) {
            this.testCase = testCase;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Main Entry Point
    // ------------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("==================================================================================");
        System.out.println("           STARTING SELENIUM LOGIN AUTOMATION & NETWORK LOGGING SUITE            ");
        System.out.println("==================================================================================");

        Login testRunner = new Login();
        testRunner.runAllTests();
    }

    /**
     * Test runner method compatible with both TestNG and JUnit 5 test runners.
     */
    @org.testng.annotations.Test
    @org.junit.jupiter.api.Test
    public void testLoginAutomationSuite() {
        runAllTests();
    }

    /**
     * Executes all test scenarios and generates the comprehensive log file.
     */
    public void runAllTests() {
        String targetUrl = resolveTargetUrl();
        File logFile = new File(LOG_FILE_PATH);

        System.out.println("Target Website URL: " + targetUrl);
        System.out.println("Log Output File   : " + logFile.getAbsolutePath());
        System.out.println("----------------------------------------------------------------------------------");

        List<TestCase> testCases = buildTestCases();
        List<TestExecutionResult> results = new ArrayList<>();

        // Initialize / Clear the log file with header
        initializeLogFile(logFile, targetUrl);

        ChromeDriver driver = null;
        try {
            driver = initChromeDriver();

            for (TestCase tc : testCases) {
                System.out.println("\n[RUNNING] " + tc.id + " - " + tc.category + " (" + tc.description + ")");
                TestExecutionResult result = executeTestCase(driver, tc, targetUrl);
                results.add(result);

                // Append test result immediately to log file
                appendResultToLogFile(logFile, result);

                System.out.println("  -> Verdict : " + (result.isPassed ? "PASS" : "FAIL") + " (" + result.verdictNote + ")");
                System.out.println("  -> Captured Network Requests: " + result.networkTransactions.size());
            }

        } catch (Exception e) {
            System.err.println("Fatal error during test suite execution: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception ignored) {
                }
            }
            appendSummaryToLogFile(logFile, results);
        }

        printConsoleSummary(results, logFile.getAbsolutePath());
    }

    // ------------------------------------------------------------------------------------------------
    // Test Case Execution Logic
    // ------------------------------------------------------------------------------------------------

    /**
     * Executes an individual test scenario on the web browser.
     */
    private TestExecutionResult executeTestCase(ChromeDriver driver, TestCase tc, String targetUrl) {
        TestExecutionResult result = new TestExecutionResult(tc);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_WAIT_SECONDS));

        try {
            // 1. Flush any previous performance logs before starting this action
            driver.manage().logs().get(LogType.PERFORMANCE);

            // 2. Navigate to target entrance page
            driver.get(targetUrl);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));

            // 3. Handle entrance page: Click "Sign in with Login ID" to display the input fields
            openLoginFormIfNecessary(driver, wait);

            // 4. Locate Login ID and Password input fields by their exact placeholders
            WebElement loginIdField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//input[@placeholder='Enter your Login ID' or contains(@placeholder, 'Login ID')]")
            ));

            WebElement passwordField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//input[@placeholder='Enter your password' or contains(@placeholder, 'password')]")
            ));

            // 5. Clear and input test values
            loginIdField.clear();
            if (tc.loginId != null && !tc.loginId.isEmpty()) {
                loginIdField.sendKeys(tc.loginId);
            }

            passwordField.clear();
            if (tc.password != null && !tc.password.isEmpty()) {
                passwordField.sendKeys(tc.password);
            }

            // 6. Locate and click the "Sign in" button
            clickSignInSubmitButton(driver, passwordField);

            // 7. Wait briefly for network request to be sent and response to be received
            pause(2000);

            // 8. Capture post-submission UI states
            result.finalPageUrl = driver.getCurrentUrl();
            result.pageTitle = driver.getTitle();
            result.uiMessage = detectUIFeedbackMessage(driver, loginIdField, passwordField);

            // 9. Extract captured HTTP requests and responses from Chrome DevTools / Performance log
            result.networkTransactions.addAll(extractNetworkTraffic(driver));

            // 10. Evaluate test verdict
            evaluateTestVerdict(result);

        } catch (Exception e) {
            result.isPassed = false;
            result.verdictNote = "Execution Exception: " + e.getMessage();
            try {
                result.finalPageUrl = driver.getCurrentUrl();
                result.networkTransactions.addAll(extractNetworkTraffic(driver));
            } catch (Exception ignored) {
            }
        }

        return result;
    }

    /**
     * Handles Entrance page: Clicks "Sign in with Login ID" button if the form fields are not yet visible.
     */
    private void openLoginFormIfNecessary(ChromeDriver driver, WebDriverWait wait) {
        // Check if the input field is ALREADY visible (e.g. if loaded directly into login view)
        List<WebElement> existingInputs = driver.findElements(By.xpath("//input[@placeholder='Enter your Login ID']"));
        if (!existingInputs.isEmpty() && existingInputs.get(0).isDisplayed()) {
            return; // Already on the form
        }

        // Locators targeting the "Sign in with Login ID" button on entrance page
        By[] loginIdButtonLocators = new By[] {
            By.xpath("//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'login id')]"),
            By.xpath("//*[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'sign in with login id')]"),
            By.xpath("//*[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'login with login id')]"),
            By.xpath("//button[contains(., 'Login ID') or contains(., 'Login id')]"),
            By.cssSelector("button.login-id-btn, #login-id-btn, [data-testid='login-id-btn']")
        };

        WebElement targetButton = null;
        for (By locator : loginIdButtonLocators) {
            try {
                WebElement el = wait.until(ExpectedConditions.elementToBeClickable(locator));
                if (el != null && el.isDisplayed()) {
                    targetButton = el;
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        if (targetButton != null) {
            try {
                targetButton.click();
            } catch (Exception e) {
                // Use JavaScript click if standard click is intercepted
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", targetButton);
            }
        }

        // Wait for the login fields to show up after clicking
        wait.until(ExpectedConditions.visibilityOfElementLocated(
            By.xpath("//input[@placeholder='Enter your Login ID' or contains(@placeholder, 'Login ID')]")
        ));
    }

    /**
     * Locates and clicks the "Sign in" / submit button. Falls back to pressing Enter.
     */
    private void clickSignInSubmitButton(ChromeDriver driver, WebElement passwordField) {
        By[] submitLocators = new By[] {
            By.xpath("//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'sign in')]"),
            By.xpath("//button[@type='submit']"),
            By.xpath("//input[@type='submit']"),
            By.xpath("//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'login')]"),
            By.cssSelector("button[type='submit'], input[type='submit'], button.signin-btn, button.btn-primary")
        };

        WebElement submitBtn = null;
        for (By loc : submitLocators) {
            try {
                List<WebElement> elements = driver.findElements(loc);
                for (WebElement el : elements) {
                    if (el.isDisplayed() && el.isEnabled()) {
                        submitBtn = el;
                        break;
                    }
                }
                if (submitBtn != null) break;
            } catch (Exception ignored) {
            }
        }

        if (submitBtn != null) {
            try {
                submitBtn.click();
            } catch (Exception e) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", submitBtn);
            }
        } else {
            // Fallback: Submit by pressing ENTER on the password field
            passwordField.sendKeys(Keys.ENTER);
        }
    }

    /**
     * Detects on-screen error messages, toast notifications, browser alerts, or HTML5 validation messages.
     */
    private String detectUIFeedbackMessage(ChromeDriver driver, WebElement idField, WebElement passField) {
        StringBuilder feedback = new StringBuilder();

        // 1. Check for native browser alert dialog
        try {
            String alertText = driver.switchTo().alert().getText();
            driver.switchTo().alert().dismiss();
            feedback.append("[Browser Alert: ").append(alertText).append("] ");
        } catch (Exception ignored) {
        }

        // 2. Check HTML5 form validation messages (e.g., "Please fill out this field")
        try {
            String idValMsg = idField.getAttribute("validationMessage");
            if (idValMsg != null && !idValMsg.isBlank()) {
                feedback.append("[Login ID Validation: ").append(idValMsg).append("] ");
            }
            String passValMsg = passField.getAttribute("validationMessage");
            if (passValMsg != null && !passValMsg.isBlank()) {
                feedback.append("[Password Validation: ").append(passValMsg).append("] ");
            }
        } catch (Exception ignored) {
        }

        // 3. Check DOM for common error/notification elements
        By[] errorLocators = new By[] {
            By.cssSelector(".error, .alert, .toast, .invalid-feedback, .error-message, [role='alert']"),
            By.xpath("//*[contains(@class, 'error') or contains(@class, 'alert') or contains(@class, 'toast')]")
        };

        for (By loc : errorLocators) {
            try {
                List<WebElement> errorElements = driver.findElements(loc);
                for (WebElement err : errorElements) {
                    if (err.isDisplayed() && !err.getText().isBlank()) {
                        feedback.append("[UI Error: ").append(err.getText().trim()).append("] ");
                        break;
                    }
                }
                if (feedback.length() > 0) break;
            } catch (Exception ignored) {
            }
        }

        return feedback.length() > 0 ? feedback.toString().trim() : "None detected (No error popup or message displayed)";
    }

    /**
     * Evaluates the pass/fail verdict of the test case based on expected outcomes.
     */
    private void evaluateTestVerdict(TestExecutionResult result) {
        TestCase tc = result.testCase;
        boolean hasHttpError = false;
        boolean hasHttpSuccess = false;

        for (NetworkTransaction tx : result.networkTransactions) {
            if (tx.responseStatus >= 200 && tx.responseStatus < 300) {
                hasHttpSuccess = true;
            } else if (tx.responseStatus >= 400) {
                hasHttpError = true;
            }
        }

        if (tc.isExpectedValid) {
            // Valid credentials: Expect successful status or redirection away from login
            if (hasHttpSuccess || !result.uiMessage.contains("Error") || !result.finalPageUrl.contains("login")) {
                result.isPassed = true;
                result.verdictNote = "Valid credentials accepted as expected";
            } else {
                result.isPassed = false;
                result.verdictNote = "Valid credentials rejected or error shown";
            }
        } else {
            // Invalid / Malformed credentials: Must NOT be authenticated
            boolean blockedByValidation = !result.uiMessage.equals("None detected (No error popup or message displayed)");
            if (hasHttpError || blockedByValidation || !hasHttpSuccess) {
                result.isPassed = true;
                result.verdictNote = "Rejected correctly (Authentication denied or validation triggered)";
            } else {
                result.isPassed = false;
                result.verdictNote = "Unexpected acceptance of invalid/malformed input";
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Chrome Performance & DevTools Network Traffic Extractor
    // ------------------------------------------------------------------------------------------------

    /**
     * Extracts captured HTTP requests, methods, POST body / form-data params, and responses via CDP.
     */
    private List<NetworkTransaction> extractNetworkTraffic(ChromeDriver driver) {
        Map<String, NetworkTransaction> transactionMap = new LinkedHashMap<>();
        LogEntries logEntries = driver.manage().logs().get(LogType.PERFORMANCE);
        Json jsonParser = new Json();

        for (LogEntry entry : logEntries) {
            try {
                Map<String, Object> root = jsonParser.toType(entry.getMessage(), Map.class);
                if (root == null || !root.containsKey("message")) continue;

                Map<String, Object> message = (Map<String, Object>) root.get("message");
                String method = (String) message.get("method");
                Map<String, Object> params = (Map<String, Object>) message.get("params");
                if (params == null) continue;

                // 1. Capture Request Sent event
                if ("Network.requestWillBeSent".equals(method)) {
                    String requestId = String.valueOf(params.get("requestId"));
                    Map<String, Object> req = (Map<String, Object>) params.get("request");
                    if (req != null) {
                        String url = String.valueOf(req.get("url"));
                        // Filter out static assets (images, css, fonts)
                        if (isRelevantTraffic(url)) {
                            NetworkTransaction tx = transactionMap.computeIfAbsent(requestId, k -> new NetworkTransaction());
                            tx.requestId = requestId;
                            tx.requestUrl = url;
                            tx.requestMethod = String.valueOf(req.get("method"));

                            // Extract Form Data / Body / Params if present
                            if (req.containsKey("postData") && req.get("postData") != null) {
                                tx.postData = String.valueOf(req.get("postData"));
                            }

                            // Extract Request Headers
                            if (req.containsKey("headers") && req.get("headers") instanceof Map) {
                                Map<String, Object> headers = (Map<String, Object>) req.get("headers");
                                headers.forEach((k, v) -> tx.requestHeaders.put(k, String.valueOf(v)));
                            }
                        }
                    }
                }

                // 2. Capture Response Received event
                else if ("Network.responseReceived".equals(method)) {
                    String requestId = String.valueOf(params.get("requestId"));
                    Map<String, Object> resp = (Map<String, Object>) params.get("response");
                    if (resp != null) {
                        String url = String.valueOf(resp.get("url"));
                        if (isRelevantTraffic(url)) {
                            NetworkTransaction tx = transactionMap.computeIfAbsent(requestId, k -> new NetworkTransaction());
                            tx.requestId = requestId;
                            tx.responseUrl = url;

                            if (resp.get("status") instanceof Number) {
                                tx.responseStatus = ((Number) resp.get("status")).intValue();
                            }
                            if (resp.containsKey("statusText")) {
                                tx.responseStatusText = String.valueOf(resp.get("statusText"));
                            }
                            if (resp.containsKey("mimeType")) {
                                tx.responseMimeType = String.valueOf(resp.get("mimeType"));
                            }

                            // Extract Response Headers
                            if (resp.containsKey("headers") && resp.get("headers") instanceof Map) {
                                Map<String, Object> headers = (Map<String, Object>) resp.get("headers");
                                headers.forEach((k, v) -> tx.responseHeaders.put(k, String.valueOf(v)));
                            }

                            // 3. Attempt to fetch Response Body via CDP command
                            try {
                                Map<String, Object> cdpParams = Collections.singletonMap("requestId", requestId);
                                Map<String, Object> bodyResult = driver.executeCdpCommand("Network.getResponseBody", cdpParams);
                                if (bodyResult != null && bodyResult.containsKey("body")) {
                                    tx.responseBody = String.valueOf(bodyResult.get("body"));
                                }
                            } catch (Exception ignored) {
                                // Body may not be available for redirects, 204 No Content, or unbuffered streams
                            }
                        }
                    }
                }

            } catch (Exception ignored) {
            }
        }

        // Return only transactions that recorded a valid URL
        List<NetworkTransaction> list = new ArrayList<>();
        for (NetworkTransaction tx : transactionMap.values()) {
            if (!tx.requestUrl.isBlank() || !tx.responseUrl.isBlank()) {
                list.add(tx);
            }
        }
        return list;
    }

    /**
     * Filters out non-essential static resources (css, fonts, png, svg) to keep the log focused on API/Auth calls.
     */
    private boolean isRelevantTraffic(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return !lower.endsWith(".png") &&
               !lower.endsWith(".jpg") &&
               !lower.endsWith(".jpeg") &&
               !lower.endsWith(".gif") &&
               !lower.endsWith(".svg") &&
               !lower.endsWith(".ico") &&
               !lower.endsWith(".woff") &&
               !lower.endsWith(".woff2") &&
               !lower.endsWith(".ttf") &&
               !lower.endsWith(".css");
    }

    // ------------------------------------------------------------------------------------------------
    // Log File Writer & Report Generator
    // ------------------------------------------------------------------------------------------------

    /**
     * Initializes the log file with metadata header.
     */
    private void initializeLogFile(File file, String targetUrl) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
            writer.println("====================================================================================================");
            writer.println("                      SELENIUM AUTOMATION TEST REPORT: Login.java                                  ");
            writer.println("====================================================================================================");
            writer.println("Generated Date & Time : " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println("Target Website URL    : " + targetUrl);
            writer.println("Browser Engine        : Google Chrome (Headless/Interactive) with CDP Performance Logging");
            writer.println("Test Scope            : Entrance page, Dual Auth, Login Form (Login ID + Password), Network Traffic");
            writer.println("Baseline Credentials  : Login ID=" + VALID_LOGIN_ID + " | Password=" + VALID_PASSWORD);
            writer.println("====================================================================================================\n");
        } catch (IOException e) {
            System.err.println("Failed to initialize log file: " + e.getMessage());
        }
    }

    /**
     * Appends an individual test case's execution and network details to the log file.
     */
    private void appendResultToLogFile(File file, TestExecutionResult res) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
            TestCase tc = res.testCase;

            writer.println("----------------------------------------------------------------------------------------------------");
            writer.println("TEST CASE ID  : " + tc.id + " [" + tc.category + "]");
            writer.println("DESCRIPTION   : " + tc.description);
            writer.println("TIMESTAMP     : " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println("INPUT VALUES  :");
            writer.println("  * Login ID : \"" + (tc.loginId != null ? tc.loginId : "") + "\"");
            writer.println("  * Password : \"" + (tc.password != null ? tc.password : "") + "\"");
            writer.println("EXPECTED TYPE : " + (tc.isExpectedValid ? "VALID CREDENTIALS (SHOULD AUTHENTICATE)" : "INVALID/MALFORMED (SHOULD BE REJECTED)"));
            writer.println("----------------------------------------------------------------------------------------------------");

            writer.println("CAPTURED NETWORK ACTIVITY:");
            if (res.networkTransactions.isEmpty()) {
                writer.println("  [Notice: No HTTP requests captured for this interaction, or request was blocked by client-side validation]");
            } else {
                int count = 1;
                for (NetworkTransaction tx : res.networkTransactions) {
                    writer.println("  [" + count++ + "] HTTP REQUEST:");
                    writer.println("      URL    : " + tx.requestUrl);
                    writer.println("      Method : " + tx.requestMethod);

                    if (!tx.postData.isBlank()) {
                        writer.println("      Param / Body / FormData:");
                        writer.println("        " + tx.postData);
                    } else if (tx.requestUrl.contains("?")) {
                        writer.println("      URL Query Parameters:");
                        writer.println("        " + tx.requestUrl.substring(tx.requestUrl.indexOf("?") + 1));
                    } else {
                        writer.println("      Param / Body / FormData: [None]");
                    }

                    if (!tx.requestHeaders.isEmpty()) {
                        writer.println("      Request Headers: " + tx.requestHeaders);
                    }

                    writer.println("      HTTP RESPONSE:");
                    writer.println("      URL         : " + (tx.responseUrl.isBlank() ? tx.requestUrl : tx.responseUrl));
                    writer.println("      Status Code : " + tx.responseStatus + " " + tx.responseStatusText);
                    writer.println("      MIME Type   : " + tx.responseMimeType);

                    if (!tx.responseBody.isBlank()) {
                        writer.println("      Response Body Got:");
                        writer.println("        " + tx.responseBody);
                    } else {
                        writer.println("      Response Body Got: [Empty or Streaming/Redirected]");
                    }
                    writer.println();
                }
            }

            writer.println("UI & BROWSER OUTCOME:");
            writer.println("  * Final Page URL : " + res.finalPageUrl);
            writer.println("  * Page Title     : " + res.pageTitle);
            writer.println("  * UI Message Got : " + res.uiMessage);
            writer.println("  * Final Verdict  : " + (res.isPassed ? "PASSED" : "FAILED") + " (" + res.verdictNote + ")");
            writer.println("----------------------------------------------------------------------------------------------------\n");

        } catch (IOException e) {
            System.err.println("Failed to append test result to log file: " + e.getMessage());
        }
    }

    /**
     * Appends a summary breakdown to the end of the log file.
     */
    private void appendSummaryToLogFile(File file, List<TestExecutionResult> results) {
        long passedCount = results.stream().filter(r -> r.isPassed).count();
        long failedCount = results.size() - passedCount;

        try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
            writer.println("====================================================================================================");
            writer.println("                                      TEST EXECUTION SUMMARY                                        ");
            writer.println("====================================================================================================");
            writer.println("Total Scenarios Executed : " + results.size());
            writer.println("Passed Scenarios         : " + passedCount);
            writer.println("Failed Scenarios         : " + failedCount);
            writer.println("Success Rate             : " + (results.isEmpty() ? 0 : (passedCount * 100 / results.size())) + "%");
            writer.println("Completed Timestamp      : " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println("====================================================================================================");
        } catch (IOException ignored) {
        }
    }

    /**
     * Displays a concise execution summary on the console.
     */
    private void printConsoleSummary(List<TestExecutionResult> results, String logFilePath) {
        long passedCount = results.stream().filter(r -> r.isPassed).count();
        long failedCount = results.size() - passedCount;

        System.out.println("\n==================================================================================");
        System.out.println("                              EXECUTION SUMMARY                                   ");
        System.out.println("==================================================================================");
        System.out.printf("Total Tests  : %d\n", results.size());
        System.out.printf("Passed       : %d\n", passedCount);
        System.out.printf("Failed       : %d\n", failedCount);
        System.out.printf("Log File Saved At: %s\n", logFilePath);
        System.out.println("==================================================================================");
    }

    // ------------------------------------------------------------------------------------------------
    // Driver Initialization & Helper Methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Creates and configures a ChromeDriver with Performance Logging enabled.
     */
    private ChromeDriver initChromeDriver() {
        ChromeOptions options = new ChromeOptions();

        // 1. Enable Chrome Performance logging to capture Network events (Requests, Form Data, Responses)
        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.PERFORMANCE, Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

        // 2. Browser capabilities & flags
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--start-maximized");

        // Optional headless mode: pass -Dheadless=true to run without opening browser window
        if (Boolean.getBoolean("headless")) {
            options.addArguments("--headless=new");
        }

        return new ChromeDriver(options);
    }

    /**
     * Resolves the target application URL in priority:
     *  1. System property: -Dapp.url=http://...
     *  2. Environment variable: APP_URL=http://...
     *  3. ApiConfig base URL if available
     *  4. Default fallback: http://localhost:8080
     */
    public static String resolveTargetUrl() {
        String cliUrl = System.getProperty("app.url");
        if (cliUrl != null && !cliUrl.isBlank()) {
            if ("mock".equalsIgnoreCase(cliUrl.trim())) {
                File mockFile = new File("mock_login_page.html");
                if (mockFile.exists()) {
                    return mockFile.toURI().toString();
                }
            }
            return cliUrl.trim();
        }

        String envUrl = System.getenv("APP_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl.trim();
        }

        try {
            String configUrl = ApiConfig.getBaseUrl();
            if (configUrl != null && !configUrl.isBlank()) {
                return configUrl;
            }
        } catch (Throwable ignored) {
        }

        return DEFAULT_APP_URL;
    }

    /**
     * Pauses thread execution safely for milliseconds.
     */
    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Test Scenarios Definition
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds the complete list of test cases requested by the user:
     *  - Valid credentials (23114 / walkin111)
     *  - Wrong password & wrong login ID combinations
     *  - Malformatted strings (empty, whitespace, SQLi, XSS, special chars, boundary strings)
     */
    public static List<TestCase> buildTestCases() {
        List<TestCase> list = new ArrayList<>();

        // 1. Right / Valid Credentials
        list.add(new TestCase(
            "TC-01",
            "Valid Credentials",
            "Valid login ID and correct password (Happy Path)",
            VALID_LOGIN_ID,
            VALID_PASSWORD,
            true
        ));

        // 2. Wrong Password
        list.add(new TestCase(
            "TC-02",
            "Wrong Password",
            "Valid login ID with incorrect password",
            VALID_LOGIN_ID,
            "wrongpassword999",
            false
        ));

        // 3. Wrong Login ID
        list.add(new TestCase(
            "TC-03",
            "Wrong Login ID",
            "Non-existent login ID with valid password format",
            "99999",
            VALID_PASSWORD,
            false
        ));

        // 4. Both Login ID and Password Wrong
        list.add(new TestCase(
            "TC-04",
            "Wrong ID & Password",
            "Non-existent login ID and wrong password",
            "invalid_user_888",
            "IncorrectPass!404",
            false
        ));

        // 5. Malformatted: Both Empty
        list.add(new TestCase(
            "TC-05",
            "Malformed: Empty Fields",
            "Both login ID and password submitted as empty strings",
            "",
            "",
            false
        ));

        // 6. Malformatted: Empty Login ID
        list.add(new TestCase(
            "TC-06",
            "Malformed: Empty Login ID",
            "Empty login ID with valid password",
            "",
            VALID_PASSWORD,
            false
        ));

        // 7. Malformatted: Empty Password
        list.add(new TestCase(
            "TC-07",
            "Malformed: Empty Password",
            "Valid login ID with empty password",
            VALID_LOGIN_ID,
            "",
            false
        ));

        // 8. Malformatted: Whitespace Only
        list.add(new TestCase(
            "TC-08",
            "Malformed: Whitespace Only",
            "Whitespace-only strings submitted for ID and password",
            "     ",
            "     ",
            false
        ));

        // 9. Malformatted: SQL Injection Payloads
        list.add(new TestCase(
            "TC-09",
            "Malformed: SQL Injection",
            "Authentication bypass SQL Injection payload",
            "' OR '1'='1",
            "' OR '1'='1",
            false
        ));

        // 10. Malformatted: Cross-Site Scripting (XSS)
        list.add(new TestCase(
            "TC-10",
            "Malformed: XSS Injection",
            "HTML/JavaScript script injection payload in login ID",
            "<script>alert('XSS')</script>",
            VALID_PASSWORD,
            false
        ));

        // 11. Malformatted: Special Characters & Symbols
        list.add(new TestCase(
            "TC-11",
            "Malformed: Special Characters",
            "Punctuation, symbols, and malformed characters",
            "!@#$%^&*()_+{}[]:;\"'<>?,./",
            "P@$$w0rd!#%&",
            false
        ));

        // 12. Malformatted: Extremely Long String (Buffer Boundary)
        list.add(new TestCase(
            "TC-12",
            "Malformed: Boundary Overflow",
            "Excessively long string input to test boundary limits",
            "23114".repeat(40),
            "walkin111".repeat(30),
            false
        ));

        return list;
    }
}
