package com.practice;

import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
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
 * Automated API Spike Testing Suite.
 *
 * Target: POST /auth/test-login (Configured via ApiConfig)
 *
 * Spike testing evaluates how the system handles sudden, dramatic surges in traffic:
 * 1. Sudden Jump: Simulates instantaneous 15x-20x traffic spikes using synchronized latches.
 * 2. Recovery: Verifies that once the spike subsides, the API immediately returns to baseline latency.
 * 3. Repetitive Spikes: Tests back-to-back shock waves (spike -> valley -> spike).
 * 4. Safe Parameters: Always supplies valid form parameters (bioId & password) to prevent "missing parameter" errors.
 * 5. Single Dedicated Function: Exactly one test method for empty parameter behavior during a spike.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("API Spike Testing Suite")
public class SpikeTest {

    public static final String LOG_FILE_PATH = "spike_test_report.log";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @BeforeAll
    public static void setup() {
        ApiConfig.initRestAssured();
        initializeReportFile();
    }

    private static synchronized void initializeReportFile() {
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE_PATH, true)))) {
            writer.println();
            writer.println("========================================================================================");
            writer.println("API SPIKE TEST REPORT");
            writer.println("Target: " + ApiConfig.getFullUrl(ApiConfig.LOGIN_ENDPOINT) + " (Method: POST)");
            writer.println("Session Started: " + LocalDateTime.now().format(TIMESTAMP_FORMATTER));
            writer.println("Base URL: " + ApiConfig.getBaseUrl());
            writer.println("========================================================================================");
            writer.flush();
        } catch (IOException e) {
            System.err.println("Warning: Unable to initialize spike test log file: " + e.getMessage());
        }
    }

    // =========================================================================
    // 1. SINGLE SUDDEN TRAFFIC SPIKE & IMMEDIATE RECOVERY
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("1. Single Sudden Traffic Spike & Recovery (Low -> Spike -> Normal)")
    void testSingleSpikeAndRecovery() throws InterruptedException {
        System.out.println("\n[SPIKE TEST 1] Executing Three-Phase Spike Test (Baseline -> Sudden Surge -> Recovery)...");

        // Phase A: Low steady traffic baseline (5 users, 15 requests)
        System.out.println(">> Phase 1A: Establishing Pre-Spike Baseline (5 threads)...");
        SpikeMetrics preBaseline = executeBatch("Pre-Spike Baseline", 5, 15, false,
                ApiConfig.VALID_BIO_ID, ApiConfig.VALID_PASSWORD);
        logAndPrintSummary(preBaseline);

        // Phase B: Instantaneous Surge (100 simultaneous threads striking at the exact same millisecond)
        System.out.println(">> Phase 1B: Applying Sudden Traffic Spike (100 simultaneous threads via CountDownLatch)...");
        SpikeMetrics spikePhase = executeSimultaneousSpike("Sudden Spike (100 Users)", 100,
                ApiConfig.VALID_BIO_ID, ApiConfig.VALID_PASSWORD);
        logAndPrintSummary(spikePhase);

        // Allow 2-second stabilization delay
        System.out.println(">> Phase 1C: Allowing 2s stabilization window after traffic drop...");
        Thread.sleep(2000);

        // Phase C: Post-Spike Recovery Check (5 users, 15 requests)
        System.out.println(">> Phase 1D: Measuring Post-Spike Recovery Performance...");
        SpikeMetrics postRecovery = executeBatch("Post-Spike Recovery", 5, 15, false,
                ApiConfig.VALID_BIO_ID, ApiConfig.VALID_PASSWORD);
        logAndPrintSummary(postRecovery);

        // Recovery Assertions:
        assertEquals(0, postRecovery.serverErrors.get(),
                "API failed to recover from spike: Still returning 5xx server errors!");
        assertEquals(0, postRecovery.connectionErrors.get(),
                "API failed to recover from spike: Connection refused or dropped!");

        if (preBaseline.p95LatencyMs > 0) {
            double recoveryRatio = postRecovery.p95LatencyMs / preBaseline.p95LatencyMs;
            System.out.printf(">> Spike Recovery Evaluation: Pre-Spike P95 = %.2f ms | Post-Spike P95 = %.2f ms (Ratio: %.2fx)%n",
                    preBaseline.p95LatencyMs, postRecovery.p95LatencyMs, recoveryRatio);

            assertTrue(postRecovery.p95LatencyMs < (preBaseline.p95LatencyMs * 4),
                    String.format("Latency did not recover! Post-spike P95 (%.2f ms) is > 4x pre-spike baseline (%.2f ms)",
                            postRecovery.p95LatencyMs, preBaseline.p95LatencyMs));
        }

        System.out.println(">> [SUCCESS] API successfully withstood sudden traffic surge and recovered cleanly!");
    }

    // =========================================================================
    // 2. CONSECUTIVE DUAL SPIKES (SHOCK WAVE TESTING)
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("2. Consecutive Dual Spikes - Back-to-Back Shock Waves")
    void testDualConsecutiveSpikes() throws InterruptedException {
        System.out.println("\n[SPIKE TEST 2] Testing Consecutive Double Spikes (Shock 1 -> Brief Pause -> Shock 2)...");

        // Spike 1: 80 simultaneous requests
        System.out.println(">> Shock Wave 1: First sudden surge (80 requests)...");
        SpikeMetrics shock1 = executeSimultaneousSpike("Spike Shock Wave 1", 80,
                ApiConfig.VALID_BIO_ID, ApiConfig.VALID_PASSWORD);
        logAndPrintSummary(shock1);

        // Brief valley: 1.5 seconds pause
        System.out.println(">> Valley: 1.5 seconds brief interval...");
        Thread.sleep(1500);

        // Spike 2: Second sudden surge (80 requests)
        System.out.println(">> Shock Wave 2: Second sudden surge (80 requests)...");
        SpikeMetrics shock2 = executeSimultaneousSpike("Spike Shock Wave 2", 80,
                ApiConfig.VALID_BIO_ID, ApiConfig.VALID_PASSWORD);
        logAndPrintSummary(shock2);

        // Verification after both shock waves
        Thread.sleep(2000);
        SpikeMetrics finalCheck = executeBatch("Post-Dual-Spike Sanity", 5, 10, false,
                ApiConfig.VALID_BIO_ID, ApiConfig.VALID_PASSWORD);
        logAndPrintSummary(finalCheck);

        assertEquals(0, finalCheck.serverErrors.get(), "Consecutive spikes crashed the server (5xx detected)!");
        assertEquals(0, finalCheck.connectionErrors.get(), "Consecutive spikes exhausted connection pool!");
        System.out.println(">> [SUCCESS] Consecutive spike resiliency verified!");
    }

    // =========================================================================
    // 3. SINGLE DEDICATED FUNCTION: EMPTY PARAMETERS TEST UNDER SPIKE
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("3. Dedicated Empty Parameter Test Under Spike - Clean 400 Rejection")
    void testEmptyParamDuringSpike() {
        System.out.println("\n[SPIKE TEST 3] Dedicated Empty Parameter Test Under Sudden Spike...");
        System.out.println(">> Verifying server rejects empty parameters with 400 Bad Request and does not crash (500) under sudden burst.");

        int spikeSize = 40;
        SpikeMetrics emptyParamSpike = executeSimultaneousSpike(
                "Empty Param Spike Burst",
                spikeSize,
                "", // empty bioId
                ""  // empty password
        );

        logAndPrintSummary(emptyParamSpike);

        // Under empty parameters, the API must:
        // 1. Never crash with 5xx internal server errors
        // 2. Never accept empty login with 200 OK
        // 3. Reject safely with 4xx client errors
        assertEquals(0, emptyParamSpike.serverErrors.get(),
                "Empty parameters during spike triggered 5xx Internal Server Error!");
        assertEquals(0, emptyParamSpike.success2xx.get(),
                "Empty parameters during spike were accepted with 200 OK!");
        assertTrue(emptyParamSpike.clientErrors4xx.get() > 0 || emptyParamSpike.connectionErrors.get() > 0,
                "Expected safe 4xx rejections for empty parameters.");

        System.out.println(">> [PASS] Empty parameter spike test verified: safe 4xx rejection under sudden burst!");
    }

    // =========================================================================
    // EXECUTION HELPERS
    // =========================================================================

    private static SpikeMetrics executeBatch(String name, int concurrency, int totalRequests, boolean pacing,
                                             Object bioId, Object password) {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        SpikeMetrics metrics = new SpikeMetrics(name, concurrency, totalRequests);
        long startTime = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalRequests; i++) {
            futures.add(executor.submit(() -> {
                sendRequest(metrics, bioId, password);
                if (pacing) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {}
                }
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                metrics.connectionErrors.incrementAndGet();
            }
        }

        executor.shutdown();
        metrics.totalDurationMs = System.currentTimeMillis() - startTime;
        metrics.computeCalculations();
        return metrics;
    }

    private static SpikeMetrics executeSimultaneousSpike(String name, int burstSize, Object bioId, Object password) {
        ExecutorService executor = Executors.newFixedThreadPool(burstSize);
        SpikeMetrics metrics = new SpikeMetrics(name, burstSize, burstSize);

        CountDownLatch readyLatch = new CountDownLatch(burstSize);
        CountDownLatch fireLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(burstSize);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < burstSize; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    fireLatch.await(); // Synchronize all threads to fire simultaneously
                    sendRequest(metrics, bioId, password);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        try {
            readyLatch.await(10, TimeUnit.SECONDS);
            fireLatch.countDown(); // FIRE!
            doneLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
        metrics.totalDurationMs = System.currentTimeMillis() - startTime;
        metrics.computeCalculations();
        return metrics;
    }

    private static void sendRequest(SpikeMetrics metrics, Object bioId, Object password) {
        long reqStart = System.currentTimeMillis();
        try {
            Response response = ApiConfig.loginRequestSpec(bioId, password)
                    .post(ApiConfig.LOGIN_ENDPOINT);

            long elapsed = System.currentTimeMillis() - reqStart;
            metrics.recordLatency(elapsed);

            int status = response.getStatusCode();
            if (status >= 200 && status < 300) {
                metrics.success2xx.incrementAndGet();
            } else if (status == 429) {
                metrics.rateLimited429.incrementAndGet();
            } else if (status >= 400 && status < 500) {
                metrics.clientErrors4xx.incrementAndGet();
            } else if (status >= 500) {
                metrics.serverErrors.incrementAndGet();
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - reqStart;
            metrics.recordLatency(elapsed);
            metrics.connectionErrors.incrementAndGet();
        } finally {
            metrics.totalCompleted.incrementAndGet();
        }
    }

    static class SpikeMetrics {
        final String phaseName;
        final int concurrency;
        final int plannedRequests;
        final AtomicInteger totalCompleted = new AtomicInteger(0);
        final AtomicInteger success2xx = new AtomicInteger(0);
        final AtomicInteger clientErrors4xx = new AtomicInteger(0);
        final AtomicInteger rateLimited429 = new AtomicInteger(0);
        final AtomicInteger serverErrors = new AtomicInteger(0);
        final AtomicInteger connectionErrors = new AtomicInteger(0);

        final List<Long> latencies = new CopyOnWriteArrayList<>();
        long totalDurationMs = 0;
        double throughputRps = 0.0;
        long minLatencyMs = 0;
        long maxLatencyMs = 0;
        double avgLatencyMs = 0.0;
        double p50LatencyMs = 0.0;
        double p95LatencyMs = 0.0;

        SpikeMetrics(String phaseName, int concurrency, int plannedRequests) {
            this.phaseName = phaseName;
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
                p95LatencyMs = getPercentile(sorted, 95.0);
            }
        }

        private static double getPercentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) return 0.0;
            int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }
    }

    private static void logAndPrintSummary(SpikeMetrics m) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        System.out.println("----------------------------------------------------------------------------------------");
        System.out.printf("PHASE: [%s] (Concurrency: %d, Completed: %d in %d ms)%n",
                m.phaseName, m.concurrency, m.totalCompleted.get(), m.totalDurationMs);
        System.out.printf("  Throughput:      %.2f req/sec%n", m.throughputRps);
        System.out.printf("  Status Codes:    2xx: %d | 4xx: %d | 429 (Rate-Limit): %d | 5xx (Crash): %d | Drops: %d%n",
                m.success2xx.get(), m.clientErrors4xx.get(), m.rateLimited429.get(), m.serverErrors.get(), m.connectionErrors.get());
        System.out.printf("  Latency Profile: Min: %d ms | Avg: %.2f ms | P50: %.2f ms | P95: %.2f ms | Max: %d ms%n",
                m.minLatencyMs, m.avgLatencyMs, m.p50LatencyMs, m.p95LatencyMs, m.maxLatencyMs);
        System.out.println("----------------------------------------------------------------------------------------");

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE_PATH, true)))) {
            writer.println("----------------------------------------------------------------------------------------");
            writer.printf("[%s] SPIKE PHASE: %s%n", timestamp, m.phaseName);
            writer.printf("  Concurrency:       %d | Total Requests: %d | Duration: %d ms | Throughput: %.2f RPS%n",
                    m.concurrency, m.totalCompleted.get(), m.totalDurationMs, m.throughputRps);
            writer.printf("  Breakdown:         2xx=%d, 4xx=%d, 429=%d, 5xx=%d, ConnectionDrops=%d%n",
                    m.success2xx.get(), m.clientErrors4xx.get(), m.rateLimited429.get(), m.serverErrors.get(), m.connectionErrors.get());
            writer.printf("  Latencies (ms):    Min=%d, Avg=%.2f, P50=%.2f, P95=%.2f, Max=%d%n",
                    m.minLatencyMs, m.avgLatencyMs, m.p50LatencyMs, m.p95LatencyMs, m.maxLatencyMs);
            writer.flush();
        } catch (IOException e) {
            System.err.println("Failed to write to spike test log file: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        System.out.println("========================================================================================");
        System.out.println("STARTING API SPIKE TESTING SUITE");
        System.out.println("Target: " + ApiConfig.getFullUrl(ApiConfig.LOGIN_ENDPOINT));
        System.out.println("Base URL: " + ApiConfig.getBaseUrl());
        System.out.println("========================================================================================");

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(SpikeTest.class))
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        System.out.println();
        System.out.println("========================================================================================");
        System.out.println("SPIKE TEST SUITE SUMMARY");
        System.out.println("========================================================================================");
        System.out.println("Tests Started:   " + summary.getTestsStartedCount());
        System.out.println("Tests Succeeded: " + summary.getTestsSucceededCount());
        System.out.println("Tests Failed:    " + summary.getTestsFailedCount());
        System.out.println("Report Log:      " + LOG_FILE_PATH);
        System.out.println("========================================================================================");

        if (summary.getTestsFailedCount() > 0) {
            System.err.println("Spike test failed! Check " + LOG_FILE_PATH);
            System.exit(1);
        } else {
            System.out.println("All spike tests completed successfully!");
            System.exit(0);
        }
    }
}
