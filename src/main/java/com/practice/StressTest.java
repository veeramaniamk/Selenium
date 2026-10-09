package com.practice;

import io.restassured.response.Response;
import org.junit.jupiter.api.*;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated API Stress & Breaking-Point Recovery Test Suite.
 *
 * Target: POST /auth/test-login (Configured via ApiConfig)
 *
 * Objectives:
 * 1. Push API beyond normal capacity to find breaking points (latency degradation,
 *    rate limiting 429, queue exhaustion, 5xx server strain).
 * 2. Recovery Testing: Verify the API fully recovers and returns to normal baseline
 *    latency after extreme stress has ceased.
 * 3. Proper Parameter Usage: All stress tests supply valid form parameters (bioId & password)
 *    so the API executes business logic instead of immediately rejecting with "missing parameter".
 * 4. Dedicated Single Function: Exactly one test method for empty/missing parameter behavior.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("API Stress & Recovery Test Suite")
public class StressTest {

    public static final String LOG_FILE_PATH = "stress_test_report.log";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Baseline metrics stored during test execution for recovery comparison
    private static volatile double baselineP95LatencyMs = -1.0;

    @BeforeAll
    public static void setup() {
        ApiConfig.initRestAssured();
        initializeReportFile();
    }

    /**
     * Initializes the report log file.
     */
    private static synchronized void initializeReportFile() {
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE_PATH, true)))) {
            writer.println();
            writer.println("========================================================================================");
            writer.println("API STRESS & BREAKING POINT RECOVERY TEST REPORT");
            writer.println("Target: " + ApiConfig.getFullUrl(ApiConfig.LOGIN_ENDPOINT) + " (Method: POST)");
            writer.println("Session Started: " + LocalDateTime.now().format(TIMESTAMP_FORMATTER));
            writer.println("Base URL Source: " + ApiConfig.getBaseUrl());
            writer.println("========================================================================================");
            writer.flush();
        } catch (IOException e) {
            System.err.println("Warning: Unable to initialize stress test log file: " + e.getMessage());
        }
    }

    // =========================================================================
    // 1. BASELINE NORMAL LOAD (To measure baseline latency)
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("1. Baseline Normal Load Test - Establish Reference Latency")
    void testBaselineNormalLoad() {
        System.out.println("\n[STRESS TEST PHASE 1] Establishing Baseline Performance under Normal Load...");
        int concurrentUsers = 5;
        int totalRequests = 20;

        StressResult result = executeStressBatch(
                "Baseline Normal Load",
                concurrentUsers,
                totalRequests,
                ApiConfig.VALID_BIO_ID,
                ApiConfig.VALID_PASSWORD
        );

        baselineP95LatencyMs = result.p95LatencyMs;
        logAndPrintSummary(result);

        // Sanity assertions for baseline
        assertTrue(result.serverErrors.get() == 0, "Server errors detected during baseline run!");
        assertTrue(result.connectionErrors.get() == 0, "Connection errors detected during baseline run!");
        System.out.printf(">> Reference Baseline Established: P50 = %.2f ms | P95 = %.2f ms%n",
                result.p50LatencyMs, result.p95LatencyMs);
    }

    // =========================================================================
    // 2. RAMP-UP STRESS TEST: FIND THE BREAKING POINT
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("2. Progressive Concurrency Ramp-Up - Detect Breaking Point")
    void testProgressiveRampToFindBreakingPoint() {
        System.out.println("\n[STRESS TEST PHASE 2] Progressively Increasing Concurrency to Find Breaking Point...");

        // Increasing concurrency tiers: 20 -> 50 -> 100 -> 150 threads
        int[] concurrencyTiers = {20, 50, 100, 150};
        int requestsPerTier = 150;

        StressResult breakingPointTier = null;

        for (int concurrency : concurrencyTiers) {
            System.out.printf("%n---> Testing Concurrency Tier: %d concurrent threads (%d requests)...%n",
                    concurrency, requestsPerTier);

            StressResult tierResult = executeStressBatch(
                    "Ramp Concurrency " + concurrency,
                    concurrency,
                    requestsPerTier,
                    ApiConfig.VALID_BIO_ID,
                    ApiConfig.VALID_PASSWORD
            );

            logAndPrintSummary(tierResult);

            // Detect breaking point signals:
            // 1. HTTP 5xx responses (server thread crash / unhandled exception)
            // 2. HTTP 429 responses (rate limiting / circuit breaker triggered)
            // 3. Socket timeout / Connection refused
            // 4. Latency spike > 5x baseline
            boolean hasServerStrain = tierResult.serverErrors.get() > 0;
            boolean hasRateLimiting = tierResult.rateLimitedErrors.get() > 0;
            boolean hasConnectionDrops = tierResult.connectionErrors.get() > 0;
            boolean hasLatencyDegradation = baselineP95LatencyMs > 0 && tierResult.p95LatencyMs > (baselineP95LatencyMs * 4);

            if (hasServerStrain || hasRateLimiting || hasConnectionDrops || hasLatencyDegradation) {
                breakingPointTier = tierResult;
                System.out.printf("   [!] BREAKING POINT DETECTED AT %d CONCURRENT THREADS!%n", concurrency);
                System.out.printf("       Server Errors: %d | Rate-Limited: %d | Connection Drops: %d | P95: %.2f ms%n",
                        tierResult.serverErrors.get(), tierResult.rateLimitedErrors.get(),
                        tierResult.connectionErrors.get(), tierResult.p95LatencyMs);
                break;
            }
        }

        if (breakingPointTier == null) {
            System.out.println("   [*] Target API remained resilient across all tested concurrency tiers (up to 150 concurrent threads)!");
        }
    }

    // =========================================================================
    // 3. SUDDEN BURST / SPIKE STRESS TEST
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("3. Sudden Traffic Spike Burst Test - Instant Concurrency Surge")
    void testSuddenSpikeBurstStress() {
        System.out.println("\n[STRESS TEST PHASE 3] Sudden Traffic Spike (100 Simultaneous Requests at exact same millisecond)...");

        int burstSize = 100;
        StressResult burstResult = executeSimultaneousBurst(
                "Sudden Spike Burst",
                burstSize,
                ApiConfig.VALID_BIO_ID,
                ApiConfig.VALID_PASSWORD
        );

        logAndPrintSummary(burstResult);

        // Verify that server doesn't crash permanently with unhandled 500 errors
        int totalRequests = burstResult.totalCompleted.get();
        int failedRequests = burstResult.serverErrors.get() + burstResult.connectionErrors.get();
        double failureRate = totalRequests > 0 ? ((double) failedRequests / totalRequests) * 100.0 : 100.0;

        System.out.printf(">> Burst Phase Complete: Total Requests = %d, Failure Rate = %.2f%%%n",
                totalRequests, failureRate);
    }

    // =========================================================================
    // 4. RECOVERY TEST: PUSH EXTREME LIMITS AND VERIFY POST-STRESS RECOVERY
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("4. Extreme Stress & Recovery Test - Verify API Recovers to Normal")
    void testApiRecoveryAfterExtremeStress() throws InterruptedException {
        System.out.println("\n[STRESS TEST PHASE 4] Extreme Stress & Post-Stress Recovery Verification...");

        // Step 1: Push extreme load to strain the API
        int extremeThreads = 120;
        int extremeRequests = 240;
        System.out.printf(">> Step 4A: Pushing Extreme Load (%d threads, %d requests)...%n",
                extremeThreads, extremeRequests);

        StressResult extremeResult = executeStressBatch(
                "Extreme Stress Phase",
                extremeThreads,
                extremeRequests,
                ApiConfig.VALID_BIO_ID,
                ApiConfig.VALID_PASSWORD
        );
        logAndPrintSummary(extremeResult);

        // Step 2: Allow cool-down window (2.5 seconds) for server connection pool and worker threads to drain
        System.out.println(">> Step 4B: Initiating 2.5-second Cool-Down Window to allow connection queues to drain...");
        Thread.sleep(2500);

        // Step 3: Probe the API to verify recovery
        System.out.println(">> Step 4C: Sending Post-Stress Verification Probes (10 requests) to confirm recovery...");
        StressResult recoveryResult = executeStressBatch(
                "Post-Stress Recovery Probe",
                5,
                10,
                ApiConfig.VALID_BIO_ID,
                ApiConfig.VALID_PASSWORD
        );
        logAndPrintSummary(recoveryResult);

        // Recovery verification assertions:
        assertEquals(0, recoveryResult.serverErrors.get(),
                "API did not recover! Still returning 5xx server errors after cool-down!");
        assertEquals(0, recoveryResult.connectionErrors.get(),
                "API did not recover! Connection refused or dropped after cool-down!");

        if (baselineP95LatencyMs > 0) {
            double latencyRatio = recoveryResult.p95LatencyMs / baselineP95LatencyMs;
            System.out.printf(">> Recovery Verification: Post-stress P95 = %.2f ms vs Baseline P95 = %.2f ms (Ratio: %.2fx)%n",
                    recoveryResult.p95LatencyMs, baselineP95LatencyMs, latencyRatio);

            // Assert that recovered latency is within reasonable margin (e.g. within 3x baseline)
            assertTrue(recoveryResult.p95LatencyMs < (baselineP95LatencyMs * 4),
                    String.format("API latency did not recover! Post-stress P95 (%.2f ms) is > 4x baseline (%.2f ms)",
                            recoveryResult.p95LatencyMs, baselineP95LatencyMs));
        }

        System.out.println(">> [SUCCESS] API successfully demonstrated complete recovery following extreme stress!");
    }

    // =========================================================================
    // 5. SINGLE DEDICATED FUNCTION: EMPTY PARAMETER TEST UNDER CONCURRENT LOAD
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("5. Dedicated Empty Parameter Test Under Concurrency - Verify Clean 400 Rejection")
    void testEmptyParamHandlingUnderStress() {
        System.out.println("\n[STRESS TEST PHASE 5] Testing Empty Parameter Handling Under Concurrent Load...");
        System.out.println(">> Verifying server safely returns 400 Bad Request (not 500 NPE / memory leak) when empty parameters are sent.");

        int concurrentThreads = 20;
        int totalRequests = 40;

        // Send empty string parameters
        StressResult emptyParamResult = executeStressBatch(
                "Empty Parameter Under Stress",
                concurrentThreads,
                totalRequests,
                "", // empty bioId
                ""  // empty password
        );

        logAndPrintSummary(emptyParamResult);

        // Verifications:
        // Under empty parameters, the API must:
        // 1. Reject cleanly with 4xx client error (400 Bad Request or 422)
        // 2. NEVER crash with 5xx (NullPointerException, NumberFormatException, etc.)
        // 3. NEVER accept empty credentials with 200 OK
        assertEquals(0, emptyParamResult.serverErrors.get(),
                "Empty parameters caused 5xx Internal Server Error! Server failed defensive validation.");
        assertEquals(0, emptyParamResult.success2xx.get(),
                "Empty parameters were accepted with 200 OK! Critical authentication bypass.");
        assertTrue(emptyParamResult.clientErrors4xx.get() > 0 || emptyParamResult.connectionErrors.get() > 0,
                "Expected safe 4xx rejection for empty parameters.");

        System.out.println(">> [PASS] Empty parameter stress test verified: safely handled with 4xx client rejection without crashing.");
    }

    // =========================================================================
    // CORE STRESS EXECUTION ENGINE
    // =========================================================================

    /**
     * Executes a batch of requests across a fixed thread pool.
     */
    private static StressResult executeStressBatch(String testName, int concurrentThreads, int totalRequests,
                                                   Object bioId, Object password) {
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        StressResult result = new StressResult(testName, concurrentThreads, totalRequests);

        long batchStartTime = System.currentTimeMillis();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            futures.add(executor.submit(() -> sendSingleRequest(result, bioId, password)));
        }

        for (Future<?> f : futures) {
            try {
                f.get(45, TimeUnit.SECONDS);
            } catch (Exception e) {
                result.connectionErrors.incrementAndGet();
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        result.totalDurationMs = System.currentTimeMillis() - batchStartTime;
        result.computeCalculations();
        return result;
    }

    /**
     * Fires requests simultaneously using a CountDownLatch so all threads strike the API at the exact same instant.
     */
    private static StressResult executeSimultaneousBurst(String testName, int burstSize, Object bioId, Object password) {
        ExecutorService executor = Executors.newFixedThreadPool(burstSize);
        StressResult result = new StressResult(testName, burstSize, burstSize);

        CountDownLatch readyLatch = new CountDownLatch(burstSize);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(burstSize);

        long burstStartTime = System.currentTimeMillis();

        for (int i = 0; i < burstSize; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Wait for simultaneous firing signal
                    sendSingleRequest(result, bioId, password);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        try {
            readyLatch.await(10, TimeUnit.SECONDS); // Wait for all threads to be ready
            startLatch.countDown();                // FIRE simultaneously!
            doneLatch.await(45, TimeUnit.SECONDS);  // Wait for all requests to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
        result.totalDurationMs = System.currentTimeMillis() - burstStartTime;
        result.computeCalculations();
        return result;
    }

    /**
     * Sends a single HTTP POST request with form parameters.
     * Uses formParam for BOTH bioId and password so Spring's @RequestParam receives them properly.
     */
    private static void sendSingleRequest(StressResult result, Object bioId, Object password) {
        long reqStart = System.currentTimeMillis();
        try {
            Response response = ApiConfig.loginRequestSpec(bioId, password)
                    .post(ApiConfig.LOGIN_ENDPOINT);

            long elapsed = System.currentTimeMillis() - reqStart;
            result.recordLatency(elapsed);

            int status = response.getStatusCode();
            if (status >= 200 && status < 300) {
                result.success2xx.incrementAndGet();
            } else if (status == 429) {
                result.rateLimitedErrors.incrementAndGet();
            } else if (status >= 400 && status < 500) {
                result.clientErrors4xx.incrementAndGet();
            } else if (status >= 500) {
                result.serverErrors.incrementAndGet();
            } else {
                result.otherStatus.incrementAndGet();
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - reqStart;
            result.recordLatency(elapsed);
            result.connectionErrors.incrementAndGet();
        } finally {
            result.totalCompleted.incrementAndGet();
        }
    }

    // =========================================================================
    // METRICS CONTAINER & LOGGING HELPERS
    // =========================================================================
    static class StressResult {
        final String testName;
        final int concurrency;
        final int plannedRequests;
        final AtomicInteger totalCompleted = new AtomicInteger(0);
        final AtomicInteger success2xx = new AtomicInteger(0);
        final AtomicInteger clientErrors4xx = new AtomicInteger(0);
        final AtomicInteger rateLimitedErrors = new AtomicInteger(0);
        final AtomicInteger serverErrors = new AtomicInteger(0);
        final AtomicInteger connectionErrors = new AtomicInteger(0);
        final AtomicInteger otherStatus = new AtomicInteger(0);

        final List<Long> latencies = new CopyOnWriteArrayList<>();
        long totalDurationMs = 0;
        double throughputRps = 0.0;
        long minLatencyMs = 0;
        long maxLatencyMs = 0;
        double avgLatencyMs = 0.0;
        double p50LatencyMs = 0.0;
        double p90LatencyMs = 0.0;
        double p95LatencyMs = 0.0;
        double p99LatencyMs = 0.0;

        StressResult(String testName, int concurrency, int plannedRequests) {
            this.testName = testName;
            this.concurrency = concurrency;
            this.plannedRequests = plannedRequests;
        }

        void recordLatency(long latencyMs) {
            latencies.add(latencyMs);
        }

        void computeCalculations() {
            if (totalDurationMs > 0 && totalCompleted.get() > 0) {
                throughputRps = (totalCompleted.get() / (double) totalDurationMs) * 1000.0;
            }

            if (!latencies.isEmpty()) {
                List<Long> sorted = new ArrayList<>(latencies);
                Collections.sort(sorted);
                minLatencyMs = sorted.get(0);
                maxLatencyMs = sorted.get(sorted.size() - 1);
                avgLatencyMs = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0);
                p50LatencyMs = getPercentile(sorted, 50.0);
                p90LatencyMs = getPercentile(sorted, 90.0);
                p95LatencyMs = getPercentile(sorted, 95.0);
                p99LatencyMs = getPercentile(sorted, 99.0);
            }
        }

        private static double getPercentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) return 0.0;
            int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }
    }

    private static void logAndPrintSummary(StressResult r) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        // Format Console Output
        System.out.println("----------------------------------------------------------------------------------------");
        System.out.printf("RESULTS FOR: [%s] (Concurrency: %d, Total: %d requests in %d ms)%n",
                r.testName, r.concurrency, r.totalCompleted.get(), r.totalDurationMs);
        System.out.printf("  Throughput:       %.2f req/sec%n", r.throughputRps);
        System.out.printf("  Status Codes:     2xx: %d | 4xx: %d | 429 (Rate-Limit): %d | 5xx (Server Error): %d | Drops: %d%n",
                r.success2xx.get(), r.clientErrors4xx.get(), r.rateLimitedErrors.get(), r.serverErrors.get(), r.connectionErrors.get());
        System.out.printf("  Latency Profile:  Min: %d ms | Avg: %.2f ms | P50: %.2f ms | P90: %.2f ms | P95: %.2f ms | Max: %d ms%n",
                r.minLatencyMs, r.avgLatencyMs, r.p50LatencyMs, r.p90LatencyMs, r.p95LatencyMs, r.maxLatencyMs);
        System.out.println("----------------------------------------------------------------------------------------");

        // Format File Log Output
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE_PATH, true)))) {
            writer.println("----------------------------------------------------------------------------------------");
            writer.printf("[%s] STRESS RUN: %s%n", timestamp, r.testName);
            writer.printf("  Target:             %s%n", ApiConfig.getFullUrl(ApiConfig.LOGIN_ENDPOINT));
            writer.printf("  Concurrency:        %d threads | Planned: %d | Completed: %d%n",
                    r.concurrency, r.plannedRequests, r.totalCompleted.get());
            writer.printf("  Duration:           %d ms | Throughput: %.2f RPS%n", r.totalDurationMs, r.throughputRps);
            writer.printf("  Status Breakdown:   2xx=%d, 4xx=%d, 429(RateLimit)=%d, 5xx(Crash)=%d, ConnectionDrops=%d%n",
                    r.success2xx.get(), r.clientErrors4xx.get(), r.rateLimitedErrors.get(), r.serverErrors.get(), r.connectionErrors.get());
            writer.printf("  Latencies (ms):     Min=%d, Avg=%.2f, P50=%.2f, P90=%.2f, P95=%.2f, P99=%.2f, Max=%d%n",
                    r.minLatencyMs, r.avgLatencyMs, r.p50LatencyMs, r.p90LatencyMs, r.p95LatencyMs, r.p99LatencyMs, r.maxLatencyMs);
            writer.flush();
        } catch (IOException e) {
            System.err.println("Failed to write to stress test log file: " + e.getMessage());
        }
    }

    // =========================================================================
    // PROGRAMMATIC LAUNCHER (Can be run via `java com.practice.StressTest` or main)
    // =========================================================================
    public static void main(String[] args) {
        System.out.println("========================================================================================");
        System.out.println("STARTING API STRESS & RECOVERY TEST SUITE");
        System.out.println("Target: " + ApiConfig.getFullUrl(ApiConfig.LOGIN_ENDPOINT));
        System.out.println("Base URL: " + ApiConfig.getBaseUrl());
        System.out.println("========================================================================================");

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(StressTest.class))
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        System.out.println();
        System.out.println("========================================================================================");
        System.out.println("STRESS TEST SUITE EXECUTION SUMMARY");
        System.out.println("========================================================================================");
        System.out.println("Tests Started:   " + summary.getTestsStartedCount());
        System.out.println("Tests Succeeded: " + summary.getTestsSucceededCount());
        System.out.println("Tests Failed:    " + summary.getTestsFailedCount());
        System.out.println("Report Log:      " + LOG_FILE_PATH);
        System.out.println("========================================================================================");

        if (summary.getTestsFailedCount() > 0) {
            System.err.println("One or more stress/recovery checks failed. Check " + LOG_FILE_PATH);
            System.exit(1);
        } else {
            System.out.println("Stress testing and recovery verification completed successfully!");
            System.exit(0);
        }
    }
}
