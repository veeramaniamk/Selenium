package com.practice;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.TestNG;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Set;

public class FirstTest {

    private WebDriver driver;
    private WebDriverWait wait;

    // TODO: Update this URL to your target application URL
    private static final String TARGET_URL = "https://360.saveetha.com/";

    @BeforeMethod
    public void setUp() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--remote-allow-origins=*");
        // options.addArguments("--headless=new"); // Uncomment if running in CI/CD or without GUI
        
        driver = new ChromeDriver(options);
        driver.manage().window().maximize();
        // Explicit wait with 10-second timeout for dynamic element rendering
        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    /**
     * Test Case 1: Functional flow for login toggle & credential submission
     * - Checks "Continue with Google" button
     * - Clicks "Sign in with Login ID" button
     * - Waits for Login ID (BioID) and password fields to appear
     * - Verifies password masking, CSRF token protection, and submits credentials
     */
    @Test
    public void testLoginWithBioIdFlow() {
        System.out.println("Navigating to: " + TARGET_URL);
        driver.get(TARGET_URL);

        // 1. Verify "Continue with Google" button is displayed
        WebElement googleBtn = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("googleSignInBtn")));
        Assert.assertTrue(googleBtn.isDisplayed(), "Verification Failed: 'Continue with Google' button should be visible.");
        System.out.println("[PASS] 'Continue with Google' button is displayed.");

        // 2. Click "Sign in with Login ID" button
        WebElement loginWithIdBtn = wait.until(ExpectedConditions.elementToBeClickable(
                By.id("showLoginFormBtn")));
        loginWithIdBtn.click();
        System.out.println("[PASS] Clicked 'Sign in with Login ID'.");

        // 3. Wait for Login ID (BioID) and Password fields to become visible
        WebElement bioIdField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("identifier")));
        WebElement passwordField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("password")));

        // 4. Verify password field is properly masked
        Assert.assertEquals(passwordField.getAttribute("type"), "password", 
                "Security Warning: Password field must have type='password' to mask sensitive input!");
        System.out.println("[PASS] Password field has type='password' (input is masked).");

        // 5. Verify CSRF Token exists in the form (Security requirement against Cross-Site Request Forgery)
        WebElement csrfToken = driver.findElement(By.name("csrf_token"));
        Assert.assertNotNull(csrfToken, "Security Alert: Form is missing CSRF token protection!");
        String tokenVal = csrfToken.getAttribute("value");
        Assert.assertTrue(tokenVal != null && !tokenVal.isEmpty(), "Security Alert: CSRF token value is empty!");
        System.out.println("[PASS] CSRF Protection verified: token is present.");

        // 6. Enter test credentials into BioID/Identifier and Password fields
        bioIdField.clear();
        bioIdField.sendKeys("test_bio_id_01");
        passwordField.clear();
        passwordField.sendKeys("SecureTestPass!2026");

        // 7. Locate and verify the Submit button
        WebElement submitButton = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("#loginForm button[type='submit']")));
        Assert.assertTrue(submitButton.isDisplayed(), "Submit button should be visible.");
        System.out.println("[PASS] Credentials entered and form is ready to submit.");
        
        // Uncomment to actually submit:
        // submitButton.click();
    }

    /**
     * Test Case 2: Security Audit for Session Cookies
     * - Audits all cookies created by the website
     * - Checks for 'HttpOnly' flag (prevents XSS cookie theft by malicious third-party scripts)
     * - Checks for 'Secure' flag (ensures cookie travels exclusively over HTTPS)
     */
    @Test
    public void testCookieSecurityAudit() {
        driver.get(TARGET_URL);

        Set<Cookie> cookies = driver.manage().getCookies();
        System.out.println("Total cookies found: " + cookies.size());

        for (Cookie cookie : cookies) {
            String cookieName = cookie.getName().toLowerCase();
            System.out.println("Inspecting Cookie: " + cookie.getName() + " | Domain: " + cookie.getDomain() +
                    " | HttpOnly: " + cookie.isHttpOnly() + " | Secure: " + cookie.isSecure());

            // If cookie stores session/auth identifiers, flags are strictly required
            if (cookieName.contains("session") || cookieName.contains("token") || 
                cookieName.contains("auth") || cookieName.contains("jwt") || cookieName.contains("id")) {
                
                Assert.assertTrue(cookie.isHttpOnly(), 
                        "Vulnerability Found: Cookie '" + cookie.getName() + 
                        "' is missing HttpOnly flag! Malicious scripts / XSS can steal this cookie.");

                Assert.assertTrue(cookie.isSecure(), 
                        "Vulnerability Found: Cookie '" + cookie.getName() + 
                        "' is missing Secure flag! It can be intercepted over unencrypted HTTP.");
            }
        }
        System.out.println("[PASS] Cookie security audit completed.");
    }

    /**
     * Test Case 3: Security Audit for LocalStorage & SessionStorage
     * - Audits browser storage for leaked credentials, passwords, or raw tokens
     * - Protects against third-party browser extensions or injected scripts reading sensitive data
     */
    @Test
    public void testLocalStorageSecurityAudit() {
        driver.get(TARGET_URL);
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // 1. Audit localStorage
        Long localLength = (Long) js.executeScript("return window.localStorage ? window.localStorage.length : 0;");
        if (localLength != null && localLength > 0) {
            for (int i = 0; i < localLength; i++) {
                String key = (String) js.executeScript("return window.localStorage.key(arguments[0]);", i);
                String val = (String) js.executeScript("return window.localStorage.getItem(arguments[0]);", key);

                if (key != null) {
                    String lowerKey = key.toLowerCase();
                    Assert.assertFalse(lowerKey.contains("password"), 
                            "Vulnerability Found: Password found in localStorage key: " + key);
                    Assert.assertFalse(lowerKey.contains("bioid") && val != null && val.contains("password"), 
                            "Vulnerability Found: Sensitive credential leaked in localStorage under: " + key);
                }
            }
        }

        // 2. Audit sessionStorage
        Long sessionLength = (Long) js.executeScript("return window.sessionStorage ? window.sessionStorage.length : 0;");
        if (sessionLength != null && sessionLength > 0) {
            for (int i = 0; i < sessionLength; i++) {
                String key = (String) js.executeScript("return window.sessionStorage.key(arguments[0]);", i);
                if (key != null) {
                    Assert.assertFalse(key.toLowerCase().contains("password"), 
                            "Vulnerability Found: Password found in sessionStorage key: " + key);
                }
            }
        }

        System.out.println("[PASS] LocalStorage and SessionStorage security audit completed.");
    }

    @AfterMethod
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    /**
     * Main method: Allows running the test suite directly as a standard Java application
     * or via TestNG runner in your IDE.
     */
    public static void main(String[] args) {
        TestNG testng = new TestNG();
        testng.setTestClasses(new Class[] { FirstTest.class });
        testng.run();
    }
}