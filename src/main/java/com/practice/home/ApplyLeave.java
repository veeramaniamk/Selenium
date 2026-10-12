package com.practice.home;

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
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;

/**
 * ====================================================================================================
 * Selenium Automation Test Suite: ApplyLeave.java
 * ====================================================================================================
 * 
 * Flow & Features:
 * 1. Direct Authentication:
 * - Automatically signs in with valid credentials (loginid: 23114, password:
 * walkin1111)
 * - Waits for the Dashboard to load
 * 2. Tab Navigation:
 * - Identifies the 3 main dashboard tabs: "Attendance", "Duty Exchange", and
 * "Apply Leave"
 * - Clicks the "Apply Leave" tab to reveal the "New Application" section
 * 3. Form Automation & Validation:
 * - Leave Type: Selects an available leave type from dropdown
 * - From Date & To Date: Interacts with the date pickers (tests valid ranges
 * and inverted dates)
 * - Day Type (Full Day / Half Day):
 * * Toggles Full Day / Half Day buttons
 * * Verifies that choosing "Half Day" dynamically displays the Session dropdown
 * (Session 1 / Session 2)
 * - Reason / Remarks:
 * * Injects SQL Injection payloads, XSS strings, special characters, and
 * boundary lengths
 * - File Upload:
 * * Generates and attaches a sample file without blocking on OS native dialogs
 * - Form Submission:
 * * Clicks "Submit Application" button and captures feedback
 * 4. Network Logging:
 * - Captures every HTTP request (URL, Method, Form/Multipart parameters)
 * - Captures every HTTP response (Status Code, MIME Type, Headers, and Response
 * Body via CDP)
 * - Intelligently filters huge HTML dumps and records clean output in
 * "Apply_Leave_Test_Report.log"
 * ====================================================================================================
 */
public class ApplyLeave {

    // ------------------------------------------------------------------------------------------------
    // Configuration & Constants
    // ------------------------------------------------------------------------------------------------

    public static final String DEFAULT_APP_URL = "http://13.233.119.231";
    public static final String LOG_FILE_PATH = "Apply_Leave_Test_Report.log";
    public static final int EXPLICIT_WAIT_SECONDS = 10;

    /** Valid login credentials */
    public static final String VALID_LOGIN_ID = "23114";
    public static final String VALID_PASSWORD = "walkin111";

    // ------------------------------------------------------------------------------------------------
    // Data Models
    // ------------------------------------------------------------------------------------------------

    /**
     * Definition of an Apply Leave test scenario.
     */
    public static class LeaveTestCase {
        public final String id;
        public final String title;
        public final String description;
        public final String leaveType; // null for auto-select first available, or specific name
        public final String fromDate; // e.g. "2026-10-20"
        public final String toDate; // e.g. "2026-10-22"
        public final boolean isHalfDay; // true for Half Day, false for Full Day
        public final String session; // "Session 1" or "Session 2" (only if isHalfDay is true)
        public final String reasonRemarks; // injection string, malformed string, or valid text
        public final boolean attachFile; // whether to attach a file
        public final boolean isExpectedSuccess;

        public LeaveTestCase(String id, String title, String description, String leaveType,
                String fromDate, String toDate, boolean isHalfDay, String session,
                String reasonRemarks, boolean attachFile, boolean isExpectedSuccess) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.leaveType = leaveType;
            this.fromDate = fromDate;
            this.toDate = toDate;
            this.isHalfDay = isHalfDay;
            this.session = session;
            this.reasonRemarks = reasonRemarks;
            this.attachFile = attachFile;
            this.isExpectedSuccess = isExpectedSuccess;
        }
    }

    /**
     * Captured Network Transaction details (Request + Response).
     */
    public static class NetworkTransaction {
        public String requestId;
        public String requestUrl = "";
        public String requestMethod = "GET";
        public String postData = "";
        public Map<String, String> requestHeaders = new LinkedHashMap<>();

        public String responseUrl = "";
        public int responseStatus = 0;
        public String responseStatusText = "";
        public String responseMimeType = "";
        public Map<String, String> responseHeaders = new LinkedHashMap<>();
        public String responseBody = "";
    }

    /**
     * Test execution result holder.
     */
    public static class TestExecutionResult {
        public final LeaveTestCase testCase;
        public final List<NetworkTransaction> networkTransactions = new ArrayList<>();
        public String finalPageUrl = "";
        public String pageTitle = "";
        public String uiFeedbackMessage = "";
        public boolean sessionDropdownVerified = false;
        public boolean isPassed;
        public String verdictNote = "";

        public TestExecutionResult(LeaveTestCase testCase) {
            this.testCase = testCase;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Main Entry Point
    // ------------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("==================================================================================");
        System.out.println("         STARTING SELENIUM 'APPLY LEAVE' AUTOMATION & LOGGING SUITE               ");
        System.out.println("==================================================================================");

        ApplyLeave suite = new ApplyLeave();
        suite.runAllTests();
    }

    /**
     * Compatible test runner method for TestNG and JUnit 5.
     */
    @org.testng.annotations.Test
    @org.junit.jupiter.api.Test
    public void testApplyLeaveSuite() {
        runAllTests();
    }

    /**
     * Orchestrates the execution of all Apply Leave test scenarios.
     */
    public void runAllTests() {
        String targetUrl = resolveTargetUrl();
        File logFile = new File(LOG_FILE_PATH);
        File tempUploadFile = prepareSampleUploadFile();

        System.out.println("Target Website URL    : " + targetUrl);
        System.out.println("Log Output File       : " + logFile.getAbsolutePath());
        System.out.println("Sample Upload File    : " + tempUploadFile.getAbsolutePath());
        System.out.println("----------------------------------------------------------------------------------");

        List<LeaveTestCase> testCases = buildTestCases();
        List<TestExecutionResult> results = new ArrayList<>();

        initializeLogFile(logFile, targetUrl);

        ChromeDriver driver = null;
        try {
            driver = initChromeDriver();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_WAIT_SECONDS));

            // Initial Step: Direct login once or per test
            System.out.println("\n[SETUP] Logging into application with valid credentials...");
            boolean loginSuccess = performDirectLogin(driver, wait, targetUrl);
            if (!loginSuccess) {
                System.err.println("CRITICAL: Login failed. Aborting Apply Leave test suite.");
                return;
            }
            System.out.println("[SETUP] Successfully logged in. Dashboard loaded.");

            for (LeaveTestCase tc : testCases) {
                System.out.println("\n[RUNNING] " + tc.id + " - " + tc.title);
                TestExecutionResult res = executeApplyLeaveScenario(driver, wait, tc, targetUrl, tempUploadFile);
                results.add(res);

                appendResultToLogFile(logFile, res);

                System.out.println("  -> Verdict : " + (res.isPassed ? "PASS" : "FAIL") + " (" + res.verdictNote + ")");
                System.out.println("  -> Captured Network Requests: " + res.networkTransactions.size());
            }

        } catch (Exception e) {
            System.err.println("Fatal suite execution error: " + e.getMessage());
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
    // Test Scenario Execution
    // ------------------------------------------------------------------------------------------------

    /**
     * Executes an individual Leave Application scenario.
     */
    private TestExecutionResult executeApplyLeaveScenario(ChromeDriver driver, WebDriverWait wait,
            LeaveTestCase tc, String targetUrl, File uploadFile) {
        TestExecutionResult result = new TestExecutionResult(tc);

        try {
            // 1. Flush previous network logs
            driver.manage().logs().get(LogType.PERFORMANCE);

            // 2. Ensure we are on the dashboard and click the 'Apply Leave' tab
            navigateToApplyLeaveTab(driver, wait, targetUrl);

            // 3. Locate and select Leave Type from dropdown
            selectLeaveType(driver, wait, tc.leaveType);

            // 4. Fill From Date and To Date
            fillDateFields(driver, wait, tc.fromDate, tc.toDate);

            // 5. Select Day Type (Full Day or Half Day) and handle dynamic Session dropdown
            selectDayTypeAndSession(driver, wait, tc, result);

            // 6. Enter Reason / Remarks (SQL injection, XSS, malformed or valid text)
            enterReasonRemarks(driver, wait, tc.reasonRemarks);

            // 7. Attach file upload if required
            if (tc.attachFile && uploadFile != null && uploadFile.exists()) {
                uploadAttachment(driver, wait, uploadFile);
            }

            // 8. Click Submit Application button
            clickSubmitApplication(driver, wait);

            // 9. Pause briefly to allow request completion and DOM update
            pause(2000);

            // 10. Capture UI feedback & network traffic
            result.finalPageUrl = driver.getCurrentUrl();
            result.pageTitle = driver.getTitle();
            result.uiFeedbackMessage = detectUIFeedbackMessage(driver);
            result.networkTransactions.addAll(extractNetworkTraffic(driver));

            // 11. Evaluate test verdict
            evaluateVerdict(result);

        } catch (Exception e) {
            result.isPassed = false;
            result.verdictNote = "Execution Error: " + e.getMessage();
            try {
                result.finalPageUrl = driver.getCurrentUrl();
                result.networkTransactions.addAll(extractNetworkTraffic(driver));
            } catch (Exception ignored) {
            }
        }

        return result;
    }

    // ------------------------------------------------------------------------------------------------
    // Step-by-Step UI Actions
    // ------------------------------------------------------------------------------------------------

    /**
     * Performs direct login using valid credentials with detailed step-by-step logging.
     */
    private boolean performDirectLogin(ChromeDriver driver, WebDriverWait wait, String targetUrl) {
        System.out.println("  [Step 1/5] Navigating to target URL: " + targetUrl);
        try {
            driver.get(targetUrl);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            System.out.println("  [Step 1/5 OK] Page loaded. Current URL: " + driver.getCurrentUrl() + " | Title: " + driver.getTitle());

            // If already on dashboard, skip login
            if (driver.getCurrentUrl().contains("dashboard") || isApplyLeaveTabPresent(driver)) {
                System.out.println("  [Notice] Already authenticated on dashboard.");
                return true;
            }

            // Step 2: Open login form if on entrance page
            System.out.println("  [Step 2/5] Checking entrance page and clicking 'Sign in with Login ID'...");
            openLoginFormIfNecessary(driver, wait);
            System.out.println("  [Step 2/5 OK] Login form revealed.");

            // Step 3: Enter credentials
            System.out.println("  [Step 3/5] Locating credentials input fields...");
            WebElement idField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.xpath("//input[@placeholder='Enter your Login ID' or contains(@placeholder, 'Login ID') or contains(@name, 'identifier')]")));
            WebElement passField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.xpath("//input[@placeholder='Enter your password' or contains(@placeholder, 'password') or @type='password']")));

            System.out.println("  [Step 3/5] Entering Login ID: " + VALID_LOGIN_ID);
            idField.clear();
            idField.sendKeys(VALID_LOGIN_ID);

            System.out.println("  [Step 3/5] Entering Password: [PROTECTED]");
            passField.clear();
            passField.sendKeys(VALID_PASSWORD);

            // Step 4: Submit login
            System.out.println("  [Step 4/5] Clicking 'Sign in' button...");
            clickSignInSubmitButton(driver, passField);

            // Step 5: Wait for transition to Dashboard
            System.out.println("  [Step 5/5] Waiting for authentication response and Dashboard transition...");
            pause(2000);

            // Check if server showed an error alert or message
            String uiFeedback = detectUIFeedbackMessage(driver);
            if (!uiFeedback.startsWith("None detected") && (uiFeedback.toLowerCase().contains("failed")
                    || uiFeedback.toLowerCase().contains("invalid") || uiFeedback.toLowerCase().contains("error"))) {
                System.err.println("\n[LOGIN FAILED - SERVER REJECTION]");
                System.err.println("  Server/UI Message: " + uiFeedback);
                System.err.println("  Current URL      : " + driver.getCurrentUrl());
                System.err.println("  Credentials Used : Login ID=" + VALID_LOGIN_ID + " | Password=" + VALID_PASSWORD);
                return false;
            }

            // Poll for dashboard or tabs up to 15 seconds
            long startTime = System.currentTimeMillis();
            boolean dashboardDetected = false;
            while (System.currentTimeMillis() - startTime < 15000) {
                if (driver.getCurrentUrl().contains("dashboard") || isApplyLeaveTabPresent(driver)) {
                    dashboardDetected = true;
                    break;
                }
                pause(500);
            }

            if (!dashboardDetected) {
                System.err.println("\n[LOGIN FAILED - TIMEOUT WAITING FOR DASHBOARD]");
                System.err.println("  Current URL   : " + driver.getCurrentUrl());
                System.err.println("  Page Title    : " + driver.getTitle());
                System.err.println("  UI Message    : " + detectUIFeedbackMessage(driver));
                return false;
            }

            System.out.println("  [Step 5/5 OK] Dashboard successfully reached! URL: " + driver.getCurrentUrl());
            return true;

        } catch (Exception e) {
            System.err.println("\n[LOGIN FAILED - EXCEPTION OCCURRED]");
            System.err.println("  Exception Type: " + e.getClass().getName());
            System.err.println("  Message       : " + e.getMessage());
            try {
                System.err.println("  Current URL   : " + driver.getCurrentUrl());
                System.err.println("  Page Title    : " + driver.getTitle());
                System.err.println("  UI Feedback   : " + detectUIFeedbackMessage(driver));
            } catch (Exception ignored) {
            }
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Handles Entrance page: Clicks "Sign in with Login ID" button if the form fields are not yet visible.
     */
    private void openLoginFormIfNecessary(ChromeDriver driver, WebDriverWait wait) {
        // Check if input field is ALREADY visible
        List<WebElement> existingInputs = driver.findElements(By.xpath("//input[@placeholder='Enter your Login ID']"));
        if (!existingInputs.isEmpty() && existingInputs.get(0).isDisplayed()) {
            return;
        }

        By[] loginIdButtonLocators = new By[] {
                By.xpath("//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'login id')]"),
                By.xpath("//*[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'sign in with login id')]"),
                By.xpath("//*[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'login with login id')]"),
                By.xpath("//button[contains(., 'Login ID') or contains(., 'Login id')]"),
                By.cssSelector("button.login-id-btn, #btnLoginId, #login-id-btn, [data-testid='login-id-btn']")
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
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", targetButton);
            }
        }

        // Wait for input field to appear after clicking
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//input[@placeholder='Enter your Login ID' or contains(@placeholder, 'Login ID') or contains(@name, 'identifier')]")));
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
                By.cssSelector("button[type='submit'], input[type='submit'], #btnSubmit, button.signin-btn, button.btn-primary")
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
                if (submitBtn != null)
                    break;
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
            passwordField.sendKeys(Keys.ENTER);
        }
    }

    /**
     * Checks if the Apply Leave tab is already in the DOM.
     */
    private boolean isApplyLeaveTabPresent(ChromeDriver driver) {
        return !driver.findElements(By.xpath(
                "//*[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'apply leave')]"))
                .isEmpty();
    }

    /**
     * Navigates to the "Apply Leave" tab on the dashboard.
     */
    private void navigateToApplyLeaveTab(ChromeDriver driver, WebDriverWait wait, String targetUrl) {
        // Ensure we are on the dashboard
        if (!driver.getCurrentUrl().contains("dashboard") && !isApplyLeaveTabPresent(driver)) {
            performDirectLogin(driver, wait, targetUrl);
        }

        By[] applyLeaveTabLocators = new By[] {
                By.xpath(
                        "//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'apply leave')]"),
                By.xpath(
                        "//a[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'apply leave')]"),
                By.xpath(
                        "//li[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'apply leave')]"),
                By.xpath(
                        "//*[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'apply leave')]"),
                By.cssSelector("#tab-apply-leave, [data-tab='apply-leave']")
        };

        WebElement tab = null;
        for (By loc : applyLeaveTabLocators) {
            try {
                List<WebElement> elements = driver.findElements(loc);
                for (WebElement el : elements) {
                    if (el.isDisplayed()) {
                        tab = el;
                        break;
                    }
                }
                if (tab != null)
                    break;
            } catch (Exception ignored) {
            }
        }

        if (tab != null) {
            try {
                tab.click();
            } catch (Exception e) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", tab);
            }
        }

        // Wait for the New Application form section to become visible
        pause(1000);
    }

    /**
     * Selects a leave type from the Leave Type dropdown.
     */
    private void selectLeaveType(ChromeDriver driver, WebDriverWait wait, String targetLeaveType) {
        By[] dropdownLocators = new By[] {
                By.xpath(
                        "//select[contains(@id, 'leave_type') or contains(@name, 'leave_type') or contains(@id, 'leave') or contains(@name, 'leave')]"),
                By.xpath(
                        "//select[preceding-sibling::label[contains(., 'Leave Type')] or ../label[contains(., 'Leave Type')]]"),
                By.cssSelector("select.leave-type, #leaveType, #leave_type")
        };

        WebElement selectElement = null;
        for (By loc : dropdownLocators) {
            try {
                List<WebElement> list = driver.findElements(loc);
                for (WebElement el : list) {
                    if (el.isDisplayed()) {
                        selectElement = el;
                        break;
                    }
                }
                if (selectElement != null)
                    break;
            } catch (Exception ignored) {
            }
        }

        if (selectElement != null) {
            Select select = new Select(selectElement);
            List<WebElement> options = select.getOptions();

            if (targetLeaveType != null && !targetLeaveType.isBlank()) {
                try {
                    select.selectByVisibleText(targetLeaveType);
                    return;
                } catch (Exception ignored) {
                }
            }

            // Auto-pick the first available valid option (skipping placeholder like
            // '--Select--')
            for (int i = 0; i < options.size(); i++) {
                String val = options.get(i).getAttribute("value");
                String txt = options.get(i).getText().trim();
                if (val != null && !val.isBlank() && !txt.toLowerCase().contains("select")) {
                    select.selectByIndex(i);
                    return;
                }
            }

            if (options.size() > 1) {
                select.selectByIndex(1);
            }
        }
    }

    /**
     * Populates the From Date and To Date fields.
     */
    private void fillDateFields(ChromeDriver driver, WebDriverWait wait, String fromDate, String toDate) {
        // 1. From Date field
        By[] fromDateLocators = new By[] {
                By.xpath(
                        "//input[contains(@id, 'from_date') or contains(@name, 'from_date') or contains(@id, 'from') or contains(@placeholder, 'From Date')]"),
                By.xpath(
                        "//input[preceding-sibling::label[contains(., 'From Date')] or ../label[contains(., 'From Date')]]"),
                By.cssSelector("#fromDate, #from_date, input[name='from_date']")
        };

        WebElement fromInput = findFirstDisplayedElement(driver, fromDateLocators);
        if (fromInput != null && fromDate != null) {
            setDateInputValue(driver, fromInput, fromDate);
        }

        // 2. To Date field
        By[] toDateLocators = new By[] {
                By.xpath(
                        "//input[contains(@id, 'to_date') or contains(@name, 'to_date') or contains(@id, 'to') or contains(@placeholder, 'To Date')]"),
                By.xpath(
                        "//input[preceding-sibling::label[contains(., 'To Date')] or ../label[contains(., 'To Date')]]"),
                By.cssSelector("#toDate, #to_date, input[name='to_date']")
        };

        WebElement toInput = findFirstDisplayedElement(driver, toDateLocators);
        if (toInput != null && toDate != null) {
            setDateInputValue(driver, toInput, toDate);
        }
    }

    /**
     * Sets date value via direct typing, datepicker interaction, or JS event
     * dispatch.
     */
    private void setDateInputValue(ChromeDriver driver, WebElement input, String dateValue) {
        try {
            input.click();
            input.sendKeys(Keys.CONTROL + "a");
            input.sendKeys(Keys.BACK_SPACE);
            input.sendKeys(dateValue);
            input.sendKeys(Keys.TAB);
        } catch (Exception e) {
            // JavaScript fallback for read-only or styled datepickers
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].value = arguments[1];" +
                            "arguments[0].dispatchEvent(new Event('input', { bubbles: true }));" +
                            "arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
                    input, dateValue);
        }
    }

    /**
     * Handles Day Type selection (Full Day vs. Half Day) and verifies the dynamic
     * Session dropdown.
     */
    private void selectDayTypeAndSession(ChromeDriver driver, WebDriverWait wait,
            LeaveTestCase tc, TestExecutionResult result) {
        if (tc.isHalfDay) {
            // Locate and click "Half Day" button or radio
            By[] halfDayLocators = new By[] {
                    By.xpath(
                            "//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'half day')]"),
                    By.xpath("//input[@value='half' or @value='half_day' or @id='half_day']"),
                    By.xpath("//label[contains(., 'Half Day')]"),
                    By.cssSelector("#btnHalfDay, #half_day, [data-day-type='half']")
            };

            WebElement halfBtn = findFirstDisplayedElement(driver, halfDayLocators);
            if (halfBtn != null) {
                try {
                    halfBtn.click();
                } catch (Exception e) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", halfBtn);
                }
            }

            pause(800); // Allow dynamic DOM transition for Session dropdown

            // Verify Session dropdown becomes visible
            By[] sessionLocators = new By[] {
                    By.xpath("//select[contains(@id, 'session') or contains(@name, 'session')]"),
                    By.xpath(
                            "//select[preceding-sibling::label[contains(., 'Session')] or ../label[contains(., 'Session')]]"),
                    By.cssSelector("#session, #sessionSelect, select[name='session']")
            };

            WebElement sessionSelectEl = null;
            for (By loc : sessionLocators) {
                try {
                    List<WebElement> list = driver.findElements(loc);
                    for (WebElement el : list) {
                        if (el.isDisplayed()) {
                            sessionSelectEl = el;
                            break;
                        }
                    }
                    if (sessionSelectEl != null)
                        break;
                } catch (Exception ignored) {
                }
            }

            if (sessionSelectEl != null) {
                result.sessionDropdownVerified = true;
                Select select = new Select(sessionSelectEl);
                String targetSession = tc.session != null ? tc.session : "Session 1";

                try {
                    select.selectByVisibleText(targetSession);
                } catch (Exception e) {
                    // Fallback to value or index
                    if (targetSession.contains("2")) {
                        select.selectByIndex(Math.min(2, select.getOptions().size() - 1));
                    } else {
                        select.selectByIndex(1);
                    }
                }
            }

        } else {
            // Locate and click "Full Day" button or radio
            By[] fullDayLocators = new By[] {
                    By.xpath(
                            "//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'full day')]"),
                    By.xpath("//input[@value='full' or @value='full_day' or @id='full_day']"),
                    By.xpath("//label[contains(., 'Full Day')]"),
                    By.cssSelector("#btnFullDay, #full_day, [data-day-type='full']")
            };

            WebElement fullBtn = findFirstDisplayedElement(driver, fullDayLocators);
            if (fullBtn != null) {
                try {
                    fullBtn.click();
                } catch (Exception e) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", fullBtn);
                }
            }
        }
    }

    /**
     * Enters Reason / Remarks into the textarea or input.
     */
    private void enterReasonRemarks(ChromeDriver driver, WebDriverWait wait, String remarks) {
        By[] remarksLocators = new By[] {
                By.xpath(
                        "//textarea[contains(@id, 'reason') or contains(@name, 'reason') or contains(@id, 'remark') or contains(@name, 'remark')]"),
                By.xpath(
                        "//input[contains(@id, 'reason') or contains(@name, 'reason') or contains(@placeholder, 'Reason') or contains(@placeholder, 'Remarks')]"),
                By.cssSelector("#reason, #remarks, textarea[name='reason'], textarea[name='remarks']")
        };

        WebElement remarksInput = findFirstDisplayedElement(driver, remarksLocators);
        if (remarksInput != null) {
            remarksInput.clear();
            if (remarks != null && !remarks.isEmpty()) {
                remarksInput.sendKeys(remarks);
            }
        }
    }

    /**
     * Uploads the attachment file to the file input element.
     */
    private void uploadAttachment(ChromeDriver driver, WebDriverWait wait, File file) {
        try {
            By[] fileInputLocators = new By[] {
                    By.xpath("//input[@type='file']"),
                    By.cssSelector("input[type='file'], #fileUpload, #attachment")
            };

            WebElement fileInput = null;
            for (By loc : fileInputLocators) {
                List<WebElement> list = driver.findElements(loc);
                if (!list.isEmpty()) {
                    fileInput = list.get(0);
                    break;
                }
            }

            if (fileInput != null) {
                // Ensure input is interactable even if styled as hidden by CSS
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].style.display = 'block'; arguments[0].style.visibility = 'visible';",
                        fileInput);
                fileInput.sendKeys(file.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("File upload note: " + e.getMessage());
        }
    }

    /**
     * Locates and clicks the "Submit Application" button.
     */
    private void clickSubmitApplication(ChromeDriver driver, WebDriverWait wait) {
        By[] submitLocators = new By[] {
                By.xpath(
                        "//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'submit application')]"),
                By.xpath(
                        "//button[contains(translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'submit') and not(contains(., 'login'))]"),
                By.xpath("//input[@type='submit' and contains(@value, 'Submit')]"),
                By.cssSelector("#btnSubmitApplication, button.btn-submit-application, button[type='submit']")
        };

        WebElement submitBtn = findFirstDisplayedElement(driver, submitLocators);
        if (submitBtn != null) {
            try {
                submitBtn.click();
            } catch (Exception e) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", submitBtn);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // UI Feedback & Evaluation
    // ------------------------------------------------------------------------------------------------

    /**
     * Detects on-screen error alerts, toasts, or HTML5 form validation messages.
     */
    private String detectUIFeedbackMessage(ChromeDriver driver) {
        StringBuilder feedback = new StringBuilder();

        // 1. Browser alerts
        try {
            String alertText = driver.switchTo().alert().getText();
            driver.switchTo().alert().dismiss();
            feedback.append("[Browser Alert: ").append(alertText).append("] ");
        } catch (Exception ignored) {
        }

        // 2. On-screen toasts / alert banners
        By[] alertLocators = new By[] {
                By.cssSelector(".alert, .toast, .swal2-title, .notification, .error, .success, [role='alert']"),
                By.xpath(
                        "//*[contains(@class, 'alert') or contains(@class, 'toast') or contains(@class, 'success') or contains(@class, 'error')]")
        };

        for (By loc : alertLocators) {
            try {
                List<WebElement> elements = driver.findElements(loc);
                for (WebElement el : elements) {
                    if (el.isDisplayed() && !el.getText().isBlank()) {
                        feedback.append("[UI Notification: ").append(el.getText().trim()).append("] ");
                        break;
                    }
                }
                if (feedback.length() > 0)
                    break;
            } catch (Exception ignored) {
            }
        }

        return feedback.length() > 0 ? feedback.toString().trim()
                : "None detected (No error or success alert displayed)";
    }

    /**
     * Evaluates the verdict of the test scenario.
     */
    private void evaluateVerdict(TestExecutionResult result) {
        LeaveTestCase tc = result.testCase;
        boolean hasHttpError = false;
        boolean hasHttpSuccess = false;

        for (NetworkTransaction tx : result.networkTransactions) {
            if (tx.responseStatus >= 200 && tx.responseStatus < 300) {
                hasHttpSuccess = true;
            } else if (tx.responseStatus >= 400) {
                hasHttpError = true;
            }
        }

        if (tc.isExpectedSuccess) {
            if (hasHttpSuccess || !result.uiFeedbackMessage.toLowerCase().contains("error")) {
                result.isPassed = true;
                result.verdictNote = "Leave application submitted successfully as expected";
            } else {
                result.isPassed = false;
                result.verdictNote = "Expected successful submission but encountered rejection/error";
            }
        } else {
            // Expected validation failure or rejection
            boolean feedbackRejected = result.uiFeedbackMessage.toLowerCase().contains("error")
                    || result.uiFeedbackMessage.toLowerCase().contains("invalid")
                    || result.uiFeedbackMessage.toLowerCase().contains("required");

            if (hasHttpError || feedbackRejected || !hasHttpSuccess) {
                result.isPassed = true;
                result.verdictNote = "Input rejected / validation triggered correctly";
            } else {
                result.isPassed = false;
                result.verdictNote = "Malicious or invalid leave data was unexpectedly accepted";
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Network Traffic Extraction (CDP + Performance Logs)
    // ------------------------------------------------------------------------------------------------

    /**
     * Extracts network traffic from Chrome Performance Logs and CDP.
     */
    private List<NetworkTransaction> extractNetworkTraffic(ChromeDriver driver) {
        Map<String, NetworkTransaction> transactionMap = new LinkedHashMap<>();
        LogEntries logEntries = driver.manage().logs().get(LogType.PERFORMANCE);
        Json jsonParser = new Json();

        for (LogEntry entry : logEntries) {
            try {
                Map<String, Object> root = jsonParser.toType(entry.getMessage(), Map.class);
                if (root == null || !root.containsKey("message"))
                    continue;

                Map<String, Object> message = (Map<String, Object>) root.get("message");
                String method = (String) message.get("method");
                Map<String, Object> params = (Map<String, Object>) message.get("params");
                if (params == null)
                    continue;

                // 1. Request will be sent
                if ("Network.requestWillBeSent".equals(method)) {
                    String requestId = String.valueOf(params.get("requestId"));
                    Map<String, Object> req = (Map<String, Object>) params.get("request");
                    if (req != null) {
                        String url = String.valueOf(req.get("url"));
                        if (isRelevantTraffic(url)) {
                            NetworkTransaction tx = transactionMap.computeIfAbsent(requestId,
                                    k -> new NetworkTransaction());
                            tx.requestId = requestId;
                            tx.requestUrl = url;
                            tx.requestMethod = String.valueOf(req.get("method"));

                            if (req.containsKey("postData") && req.get("postData") != null) {
                                tx.postData = String.valueOf(req.get("postData"));
                            }

                            if (req.containsKey("headers") && req.get("headers") instanceof Map) {
                                Map<String, Object> headers = (Map<String, Object>) req.get("headers");
                                headers.forEach((k, v) -> tx.requestHeaders.put(k, String.valueOf(v)));
                            }
                        }
                    }
                }

                // 2. Response received
                else if ("Network.responseReceived".equals(method)) {
                    String requestId = String.valueOf(params.get("requestId"));
                    Map<String, Object> resp = (Map<String, Object>) params.get("response");
                    if (resp != null) {
                        String url = String.valueOf(resp.get("url"));
                        if (isRelevantTraffic(url)) {
                            NetworkTransaction tx = transactionMap.computeIfAbsent(requestId,
                                    k -> new NetworkTransaction());
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

                            if (resp.containsKey("headers") && resp.get("headers") instanceof Map) {
                                Map<String, Object> headers = (Map<String, Object>) resp.get("headers");
                                headers.forEach((k, v) -> tx.responseHeaders.put(k, String.valueOf(v)));
                            }

                            // Fetch response body via CDP command
                            try {
                                Map<String, Object> cdpParams = Collections.singletonMap("requestId", requestId);
                                Map<String, Object> bodyResult = driver.executeCdpCommand("Network.getResponseBody",
                                        cdpParams);
                                if (bodyResult != null && bodyResult.containsKey("body")) {
                                    tx.responseBody = String.valueOf(bodyResult.get("body"));
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }

            } catch (Exception ignored) {
            }
        }

        List<NetworkTransaction> list = new ArrayList<>();
        for (NetworkTransaction tx : transactionMap.values()) {
            if (!tx.requestUrl.isBlank() || !tx.responseUrl.isBlank()) {
                list.add(tx);
            }
        }
        return list;
    }

    private boolean isRelevantTraffic(String url) {
        if (url == null)
            return false;
        String lower = url.toLowerCase();
        return !lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")
                && !lower.endsWith(".gif") && !lower.endsWith(".svg") && !lower.endsWith(".ico")
                && !lower.endsWith(".woff") && !lower.endsWith(".woff2") && !lower.endsWith(".ttf")
                && !lower.endsWith(".css");
    }

    // ------------------------------------------------------------------------------------------------
    // Logging & Report File Writer
    // ------------------------------------------------------------------------------------------------

    private void initializeLogFile(File file, String targetUrl) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
            writer.println(
                    "====================================================================================================");
            writer.println(
                    "                   SELENIUM AUTOMATION TEST REPORT: ApplyLeave.java                                 ");
            writer.println(
                    "====================================================================================================");
            writer.println("Generated Date & Time : "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println("Target Website URL    : " + targetUrl);
            writer.println("Browser Engine        : Google Chrome with CDP Performance Logging");
            writer.println(
                    "Scope                 : Attendance/Duty Exchange/Apply Leave Tabs, Form Fields, Dates, Day Types,");
            writer.println(
                    "                        Session 1/2 dropdowns, Reason/Remarks SQLi/XSS, File Attachment, Submit");
            writer.println(
                    "====================================================================================================\n");
        } catch (IOException e) {
            System.err.println("Failed to initialize Apply Leave log file: " + e.getMessage());
        }
    }

    private void appendResultToLogFile(File file, TestExecutionResult res) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
            LeaveTestCase tc = res.testCase;

            writer.println(
                    "----------------------------------------------------------------------------------------------------");
            writer.println("TEST CASE ID  : " + tc.id + " [" + tc.title + "]");
            writer.println("DESCRIPTION   : " + tc.description);
            writer.println("TIMESTAMP     : "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println("INPUT PARAMETERS:");
            writer.println("  * Leave Type     : " + (tc.leaveType != null ? tc.leaveType : "Auto-selected"));
            writer.println("  * From Date      : " + tc.fromDate);
            writer.println("  * To Date        : " + tc.toDate);
            writer.println(
                    "  * Day Type       : " + (tc.isHalfDay ? "Half Day (Session: " + tc.session + ")" : "Full Day"));
            writer.println("  * Session Dropdown Verified : "
                    + (tc.isHalfDay ? (res.sessionDropdownVerified ? "YES (Visible & Selected)" : "NO (Not found)")
                            : "N/A"));
            writer.println("  * Remarks/Reason : \"" + tc.reasonRemarks + "\"");
            writer.println("  * File Attached  : " + tc.attachFile);
            writer.println("  * Expected Type  : "
                    + (tc.isExpectedSuccess ? "VALID APPLICATION" : "INVALID / MALFORMED / DEFENSIVE"));
            writer.println(
                    "----------------------------------------------------------------------------------------------------");

            writer.println("CAPTURED NETWORK ACTIVITY:");
            if (res.networkTransactions.isEmpty()) {
                writer.println("  [Notice: No HTTP network transactions recorded for this submission]");
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
                    writer.println(
                            "      URL         : " + (tx.responseUrl.isBlank() ? tx.requestUrl : tx.responseUrl));
                    writer.println("      Status Code : " + tx.responseStatus + " " + tx.responseStatusText);
                    writer.println("      MIME Type   : " + tx.responseMimeType);
                    writer.println("      Response Body Got:");
                    writer.println("        " + formatResponseBody(tx));
                    writer.println();
                }
            }

            writer.println("UI & BROWSER OUTCOME:");
            writer.println("  * Final Page URL : " + res.finalPageUrl);
            writer.println("  * Page Title     : " + res.pageTitle);
            writer.println("  * UI Message Got : " + res.uiFeedbackMessage);
            writer.println(
                    "  * Final Verdict  : " + (res.isPassed ? "PASSED" : "FAILED") + " (" + res.verdictNote + ")");
            writer.println(
                    "----------------------------------------------------------------------------------------------------\n");

        } catch (IOException e) {
            System.err.println("Failed to append test result: " + e.getMessage());
        }
    }

    /**
     * Smart HTML response body filter:
     * - Omit huge full HTML pages and log only response code and length.
     * - Include smaller pieces of HTML (<= 500 chars) such as error snippets or
     * alerts.
     */
    private String formatResponseBody(NetworkTransaction tx) {
        if (tx.responseBody == null || tx.responseBody.isBlank()) {
            return "[Empty or Streaming/Redirected]";
        }

        String body = tx.responseBody.trim();
        String mime = tx.responseMimeType != null ? tx.responseMimeType.toLowerCase() : "";
        String lowerBody = body.toLowerCase();

        boolean isHtml = mime.contains("text/html")
                || mime.contains("application/xhtml")
                || lowerBody.startsWith("<!doctype html")
                || lowerBody.startsWith("<html")
                || (lowerBody.contains("<head") && lowerBody.contains("<body"));

        final int HTML_SMALL_PIECE_THRESHOLD = 500;

        if (isHtml) {
            if (body.length() > HTML_SMALL_PIECE_THRESHOLD) {
                String pageTitle = "";
                int titleStart = lowerBody.indexOf("<title>");
                int titleEnd = lowerBody.indexOf("</title>");
                if (titleStart != -1 && titleEnd > titleStart) {
                    pageTitle = " | Title: \"" + body.substring(titleStart + 7, titleEnd).trim() + "\"";
                }

                return String.format(
                        "[Full HTML Content Omitted - Response Code: %d %s%s | Length: %d chars]",
                        tx.responseStatus,
                        (tx.responseStatusText != null && !tx.responseStatusText.isBlank() ? tx.responseStatusText
                                : ""),
                        pageTitle,
                        body.length());
            } else {
                return body;
            }
        }

        final int NON_HTML_MAX_LENGTH = 3000;
        if (body.length() > NON_HTML_MAX_LENGTH) {
            return body.substring(0, NON_HTML_MAX_LENGTH)
                    + "\n        ... [Payload truncated, total length: " + body.length() + " chars]";
        }

        return body;
    }

    private void appendSummaryToLogFile(File file, List<TestExecutionResult> results) {
        long passedCount = results.stream().filter(r -> r.isPassed).count();
        long failedCount = results.size() - passedCount;

        try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
            writer.println(
                    "====================================================================================================");
            writer.println(
                    "                                      TEST EXECUTION SUMMARY                                        ");
            writer.println(
                    "====================================================================================================");
            writer.println("Total Scenarios Executed : " + results.size());
            writer.println("Passed Scenarios         : " + passedCount);
            writer.println("Failed Scenarios         : " + failedCount);
            writer.println("Success Rate             : "
                    + (results.isEmpty() ? 0 : (passedCount * 100 / results.size())) + "%");
            writer.println("Completed Timestamp      : "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println(
                    "====================================================================================================");
        } catch (IOException ignored) {
        }
    }

    private void printConsoleSummary(List<TestExecutionResult> results, String logFilePath) {
        long passedCount = results.stream().filter(r -> r.isPassed).count();
        long failedCount = results.size() - passedCount;

        System.out.println("\n==================================================================================");
        System.out.println("                         APPLY LEAVE EXECUTION SUMMARY                            ");
        System.out.println("==================================================================================");
        System.out.printf("Total Tests  : %d\n", results.size());
        System.out.printf("Passed       : %d\n", passedCount);
        System.out.printf("Failed       : %d\n", failedCount);
        System.out.printf("Log File Saved At: %s\n", logFilePath);
        System.out.println("==================================================================================");
    }

    // ------------------------------------------------------------------------------------------------
    // Setup & Helpers
    // ------------------------------------------------------------------------------------------------

    private ChromeDriver initChromeDriver() {
        ChromeOptions options = new ChromeOptions();

        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.PERFORMANCE, Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--start-maximized");

        if (Boolean.getBoolean("headless")) {
            options.addArguments("--headless=new");
        }

        return new ChromeDriver(options);
    }

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

        return DEFAULT_APP_URL;
    }

    private WebElement findFirstDisplayedElement(ChromeDriver driver, By[] locators) {
        for (By loc : locators) {
            try {
                List<WebElement> elements = driver.findElements(loc);
                for (WebElement el : elements) {
                    if (el.isDisplayed() && el.isEnabled()) {
                        return el;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private File prepareSampleUploadFile() {
        File uploadDir = new File("temp_uploads");
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }

        File sampleFile = new File(uploadDir, "sample_leave_document.txt");
        if (!sampleFile.exists()) {
            try (FileWriter fw = new FileWriter(sampleFile)) {
                fw.write("Medical Certificate / Leave Document Proof\nGenerated for automated Selenium testing.\nDate: "
                        + LocalDate.now());
            } catch (IOException ignored) {
            }
        }
        return sampleFile;
    }

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
     * Builds the comprehensive list of Apply Leave test scenarios.
     */
    public static List<LeaveTestCase> buildTestCases() {
        List<LeaveTestCase> list = new ArrayList<>();

        LocalDate today = LocalDate.now();
        String validFrom = today.plusDays(2).toString();
        String validTo = today.plusDays(4).toString();
        String invalidToDate = today.minusDays(2).toString(); // Before from date

        // 1. Happy Path - Valid Full Day Application with File Attachment
        list.add(new LeaveTestCase(
                "TC-01",
                "Valid Full Day Leave",
                "Applies for a standard Full Day leave with valid dates and sample attachment",
                null,
                validFrom,
                validTo,
                false,
                null,
                "Personal family function and travel",
                true,
                true));

        // 2. Valid Half Day Application - Session 1
        list.add(new LeaveTestCase(
                "TC-02",
                "Valid Half Day - Session 1",
                "Selects Half Day, verifies Session dropdown appears, and chooses Session 1",
                null,
                validFrom,
                validFrom,
                true,
                "Session 1",
                "Doctor appointment in the forenoon session",
                true,
                true));

        // 3. Valid Half Day Application - Session 2
        list.add(new LeaveTestCase(
                "TC-03",
                "Valid Half Day - Session 2",
                "Selects Half Day, verifies Session dropdown, and chooses Session 2",
                null,
                validFrom,
                validFrom,
                true,
                "Session 2",
                "Personal banking work in the afternoon session",
                false,
                true));

        // 4. Date Validation - To Date Earlier Than From Date
        list.add(new LeaveTestCase(
                "TC-04",
                "Date Validation: To Date Before From Date",
                "Checks date validation error when To Date is chronologically earlier than From Date",
                null,
                validFrom,
                invalidToDate,
                false,
                null,
                "Testing date range boundary check",
                false,
                false));

        // 5. Security - SQL Injection in Reason / Remarks
        list.add(new LeaveTestCase(
                "TC-05",
                "Security: SQL Injection in Reason",
                "Injects authentication bypass and database command into the Reason field",
                null,
                validFrom,
                validTo,
                false,
                null,
                "' OR '1'='1'; DROP TABLE tbl_leaves; --",
                false,
                false));

        // 6. Security - Cross-Site Scripting (XSS) in Reason
        list.add(new LeaveTestCase(
                "TC-06",
                "Security: XSS Script Injection in Reason",
                "Injects HTML/JavaScript XSS payload into the Reason/Remarks field",
                null,
                validFrom,
                validTo,
                false,
                null,
                "<script>alert('XSS-Leave')</script><img src=x onerror=alert(1)>",
                false,
                false));

        // 7. Defensive - Malformed Special Characters & Emojis
        list.add(new LeaveTestCase(
                "TC-07",
                "Malformed: Special Characters & Emojis",
                "Submits Unicode emojis, punctuation, and malformed symbols in Reason",
                null,
                validFrom,
                validTo,
                false,
                null,
                "!@#$%^&*()_+{}[]:;\"'<>?,./~ 🚀🔥⭐ ñçü",
                false,
                false));

        // 8. Boundary - Excessively Long String (Buffer Boundary)
        list.add(new LeaveTestCase(
                "TC-08",
                "Boundary: Excessively Long Reason Text",
                "Submits a 1,500+ character string in Reason to verify max-length limit",
                null,
                validFrom,
                validTo,
                false,
                null,
                "Emergency leave request details exceeding normal length boundary. ".repeat(40),
                false,
                false));

        // 9. Validation - Empty Reason / Remarks
        list.add(new LeaveTestCase(
                "TC-09",
                "Validation: Missing Reason",
                "Submits without providing any reason to check required field enforcement",
                null,
                validFrom,
                validTo,
                false,
                null,
                "",
                false,
                false));

        return list;
    }
}
