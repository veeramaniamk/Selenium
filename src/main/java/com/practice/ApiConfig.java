package com.practice;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/**
 * Reusable Centralized API Configuration.
 * 
 * Centralizes the base URL, endpoint constants, valid credentials,
 * and standard request specifications across all test suites
 * (Security, Stress, Functional, etc.) to prevent duplicate configuration.
 */
public class ApiConfig {

    // Default configuration values
    public static final String DEFAULT_HOST = "http://localhost:8080";
    public static final String LOGIN_ENDPOINT = "/auth/test-login";

    // Valid baseline credentials for tests
    public static final Long VALID_BIO_ID = 1001L;
    public static final String VALID_PASSWORD = "ValidSecurePassword123!";

    // Dynamic base URL storage
    private static String customBaseUrl = null;

    /**
     * Resolves the API Base URL in order of priority:
     * 1. Programmatically set via ApiConfig.setBaseUrl(...)
     * 2. System Property: -Dapi.base.url=http://...
     * 3. Environment Variable: API_BASE_URL=http://...
     * 4. Default fallback: http://localhost:8080
     *
     * @return Resolved base URL string without trailing slash
     */
    public static String getBaseUrl() {
        if (customBaseUrl != null && !customBaseUrl.isBlank()) {
            return cleanUrl(customBaseUrl);
        }

        String sysProp = System.getProperty("api.base.url");
        if (sysProp != null && !sysProp.isBlank()) {
            return cleanUrl(sysProp);
        }

        String envVar = System.getenv("API_BASE_URL");
        if (envVar != null && !envVar.isBlank()) {
            return cleanUrl(envVar);
        }

        return DEFAULT_HOST;
    }

    /**
     * Programmatically override the base URL for the current JVM run.
     *
     * @param baseUrl New base URL
     */
    public static void setBaseUrl(String baseUrl) {
        customBaseUrl = baseUrl;
        RestAssured.baseURI = getBaseUrl();
    }

    /**
     * Resolves a full target URL for a given relative endpoint path.
     *
     * @param endpoint Relative endpoint path (e.g. "/auth/test-login")
     * @return Full URL (e.g. "http://localhost:8080/auth/test-login")
     */
    public static String getFullUrl(String endpoint) {
        String base = getBaseUrl();
        if (endpoint == null || endpoint.isBlank()) {
            return base;
        }
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }
        return base + endpoint;
    }

    /**
     * Applies standard RestAssured defaults (baseURI, timeouts).
     */
    public static void initRestAssured() {
        RestAssured.baseURI = getBaseUrl();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    /**
     * Pre-configured RequestSpecification for POST /auth/test-login with valid form parameters.
     * Uses formParam for BOTH bioId and password so that Spring's @RequestParam 
     * never fails with "missing parameter error".
     *
     * @return Prepared RequestSpecification
     */
    public static RequestSpecification validLoginRequestSpec() {
        return RestAssured.given()
                .baseUri(getBaseUrl())
                .contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .formParam("bioId", VALID_BIO_ID)
                .formParam("password", VALID_PASSWORD);
    }

    /**
     * Pre-configured RequestSpecification for POST /auth/test-login with custom form parameters.
     *
     * @param bioId    The bioId value (Long or String or empty)
     * @param password The password value
     * @return Prepared RequestSpecification
     */
    public static RequestSpecification loginRequestSpec(Object bioId, Object password) {
        RequestSpecification spec = RestAssured.given()
                .baseUri(getBaseUrl())
                .contentType("application/x-www-form-urlencoded; charset=UTF-8");

        if (bioId != null) {
            spec.formParam("bioId", bioId);
        }
        if (password != null) {
            spec.formParam("password", password);
        }
        return spec;
    }

    private static String cleanUrl(String url) {
        String trimmed = url.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
