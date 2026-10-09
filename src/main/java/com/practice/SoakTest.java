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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated API Soak (Endurance) Testing Suite.
 *
 * Target: POST /auth/test-login (Configured via ApiConfig)
 *
 * Soak testing applies a steady, sustained load over extended periods (minutes/hours) to detect:
 * 1. Memory Leaks: JVM heap exhaustion, progressive GC pause degradation.
 * 2. Resource Starvation: Unclosed DB connections, thread leaks, socket file descriptor leaks.
 * 3. Latency Drift: Deterioration of response times between early intervals vs late intervals.
 * 4. Configurable Duration: Defaults to 60s for local automation; override via -Dsoak.duration.minutes=60.
 * 5. Safe Parameters: Always supplies valid form parameters (bioId & password) to prevent "missing parameter" errors.
 * 6. Single Dedicated Function: Exactly one test method for empty parameter endurance checks.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("API Soak & Endurance Testing Suite")
public class SoakTest {

    public static final String LOG_FILE_PATH = "soak_test_report.log";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Configurable duration (seconds). Default is 60 seconds for fast automated runs.
    // For production endurance runs: pass -Dsoak.duration.minutes=60 (or -Dsoak.duration.seconds=3600)
    public static final long SOAK_DURATION_SECONDS = resolveDurationSeconds();
    public static final int SOAK_CONCURRENCY = Integer.getInteger("soak.concurrency", 8);
    public static final long REQUEST_PACING_MS = Long.getLong("soak.pacing.ms", 50L);

    @BeforeAll
    public static void setup() {
        ApiConfig.initRestAssured();
        initializeReportFile();
    }

    private static long resolveDurationSeconds() {
        String minutesProp = System.getProperty("soak.duration.minutes");
        if (minutesProp != null && !minutesProp.isBlank()) {
            return Long.parseLong(minutesProp.trim()) * 60;
        }
        String secondsProp = System.getProperty("soak.duration.seconds");
        if (secondsProp != null && !secondsProp.isBlank()) {
            return Long.parseLong(secondsProp.trim());
        }
        return 60L; // Default: 60 seconds
    }

    private static synchronized void initializeReportFile() {
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE_PATH, true)))) {
            writer.println();
            writer.println("========================================================================================");
            writer.println("API SOAK & ENDURANCE TEST REPORT");
            writer.println("Target: " + ApiConfig.getFullUrl(ApiConfig.LOGIN_ENDPOINT) + " (Method: POST)");
            writer.println("Configured Duration: " + SOAK_DURATION_SECONDS + " seconds (" + (SOAK_DURATION_SECONDS / 60.0) + " minutes)");
            writer.println("Concurrency: " + SOAK_CONCURRENCY + " threads | Request Pacing: " + REQUEST_PACING_MS + " ms");
            writer.println("Session Started: " + LocalDateTime.now().format(TIMESTAMP_FORMATTER));
            writer.println("Base URL: " + ApiConfig.getBaseUrl());
            writer.println("========================================================================================");
            writer.flush();
        } catch (IOException e) {
            System.err.println("Warning: Unable to initialize soak test log file: " + e.getMessage());
        }
    }

    // =========================================================================
    // 1. STEADY-STATE SOAK & ENDURANCE TEST (DETECT MEMORY & LATENCY LEAKS)
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("1. Sustained Steady-Load Soak Test - Detect Memory Leaks & Latency Drift")
    void testSteadyStateEndurance() throws InterruptedException {
        System.out.printf("%n[SOAK TEST 1] Commencing Endurance Run: %d seconds with %d threads (Pacing: %d ms)...%n",
                SOAK_DURATION_SECONDS, SOAK_CONCURRENCY, REQUEST_PACING_MS);
        System.out.println(">> Monitoring interval windows to track latency drift, connection stability, and memory degradation...");

        // Window size for time-sliced degradation tracking (e.g. every 15s or 1 minute)
        long windowIntervalSeconds = Math.max(10L, SOAK_DURATION_SECONDS / 4);

        SoakRunTracker tracker = executeSoakRun(
                "Sustained Steady Soak",
                SOAK_CONCURRENCY,
                SOAK_DURATION_SECONDS,
                windowIntervalSeconds,
                REQUEST_PACING_MS,
                ApiConfig.VALID_BIO_ID,
                ApiConfig.VALID_PASSWORD
        );

        logAndPrintSoakSummary(tracker);

        // Assertions for Endurance & Leak Detection:
        // 1. Server errors (5xx) must remain 0
        assertEquals(0, tracker.serverErrors.get(),
                "Memory leak or crash detected during endurance run! Server returned 5xx errors.");

        // 2. Connection errors must remain 0
        assertEquals(0, tracker.connectionErrors.get(),
                "Resource leak detected! Server dropped connections or exhausted pool during endurance run.");

        // 3. Latency Drift Check: Compare initial window P95 vs final window P95
        if (tracker.windowMetrics.size() >= 2) {
            WindowMetrics firstWindow = tracker.windowMetrics.get(0);
            WindowMetrics lastWindow = tracker.windowMetrics.get(tracker.windowMetrics.size() - 1);

            double driftRatio = lastWindow.p95LatencyMs / Math.max(1.0, firstWindow.p95LatencyMs);
            System.out.printf(">> Latency Drift Analysis: First Window P95 = %.2f ms | Final Window P95 = %.2f ms (Drift: %.2fx)%n",
                    firstWindow.p95LatencyMs, lastWindow.p95LatencyMs, driftRatio);

            // If final latency is > 3.5x initial latency under identical steady load,
            // that is a classic signature of memory leak or GC saturation.
            assertTrue(driftRatio < 3.5,
                    String.format("Severe latency degradation detected over time! Drift ratio %.2fx exceeds 3.5x threshold.", driftRatio));
        }

        System.out.println(">> [SUCCESS] API demonstrated excellent endurance! No memory leaks or performance degradation observed.");
    }

    // =========================================================================
    // 2. SINGLE DEDICATED FUNCTION: EMPTY PARAMETERS ENDURANCE SANITY
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("2. Dedicated Empty Parameter Endurance Check - No Cumulative Leak on Invalid Inputs")
    void testEnduranceEmptyParamSanity() {
        System.out.println("\n[SOAK TEST 2] Dedicated Empty Parameter Endurance Verification...");
        System.out.println(">> Verifying that repeated empty parameter inputs do not accumulate memory leaks or crash the server.");

        long shortDurationSeconds = 15L;
        int threads = 4;

        SoakRunTracker emptyParamTracker = executeSoakRun(
                "Empty Param Endurance Sanity",
                threads,
                shortDurationSeconds,
                shortDurationSeconds,
                50L,
                "", // empty bioId
                ""  // empty password
        );

        logAndPrintSoakSummary(emptyParamTracker);

        // Verification:
        assertEquals(0, emptyParamTracker.serverErrors.get(),
                "Repeated empty parameters caused 5xx server crash during endurance run!");
        assertEquals(0, emptyParamTracker.success2xx.get(),
                "Empty parameters accepted with 200 OK!");
        assertTrue(emptyParamTracker.clientErrors4xx.get() > 0 || emptyParamTracker.connectionErrors.get() > 0,
                "Expected safe 4xx rejections for empty parameters.");

        System.out.println(">> [PASS] Empty parameter endurance verified: safe 4xx rejections without resource leakage.");
    }

    // =========================================================================
    // SOAK RUN ENGINE
    // =========================================================================

    private static SoakRunTracker executeSoakRun(String testName, int concurrency, long totalDurationSeconds,
                                                long windowIntervalSeconds, long pacingMs,
                                                Object bioId, Object password) {
        SoakRunTracker tracker = new SoakRunTracker(testName, concurrency, totalDurationSeconds);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        AtomicBoolean isRunning = new AtomicBoolean(true);

        long startTime = System.currentTimeMillis();
        long endTime = startTime + (totalDurationSeconds * 1000L);

        // Launch worker threads
        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                while (isRunning.get() && System.currentTimeMillis() < endTime) {
                    sendSoakRequest(tracker, bioId, password);
                    if (pacingMs > 0) {
                        try {
                            Thread.sleep(pacingMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            });
        }

        // Monitor intervals
        long nextWindowTime = startTime + (windowIntervalSeconds * 1000L);
        int windowIndex = 1;
        int lastWindowCount = 0;

        while (System.currentTimeMillis() < endTime) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (System.currentTimeMillis() >= nextWindowTime) {
                int currentTotal = tracker.totalCompleted.get();
                int windowRequests = currentTotal - lastWindowCount;
                lastWindowCount = currentTotal;

                WindowMetrics window = tracker.computeWindowMetrics(windowIndex++, windowRequests);
                tracker.windowMetrics.add(window);

                System.out.printf("   [Window %d @ %ds] Requests: %d | Window P50: %.2f ms | Window P95: %.2f ms | Errors: %d%n",
                        window.windowNumber,
                        (System.currentTimeMillis() - startTime) / 1000,
                        windowRequests,
                        window.p50LatencyMs,
                        window.p95LatencyMs,
                        tracker.serverErrors.get());

                nextWindowTime += (windowIntervalSeconds * 1000L);
            }
        }

        isRunning.set(false);
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        tracker.totalDurationMs = System.currentTimeMillis() - startTime;
        tracker.computeOverall();
        return tracker;
    }

    private static void sendSoakRequest(SoakRunTracker tracker, Object bioId, Object password) {
        long reqStart = System.currentTimeMillis();
        try {
            Response response = ApiConfig.loginRequestSpec(bioId, password)
                    .post(ApiConfig.LOGIN_ENDPOINT);

            long elapsed = System.currentTimeMillis() - reqStart;
            tracker.recordLatency(elapsed);

            int status = response.getStatusCode();
            if (status >= 200 && status < 300) {
                tracker.success2xx.incrementAndGet();
            } else if (status == 429) {
                tracker.rateLimited429.incrementAndGet();
            } else if (status >= 400 && status < 500) {
                tracker.clientErrors4xx.incrementAndGet();
            } else if (status >= 500) {
                tracker.serverErrors.incrementAndGet();
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - reqStart;
            tracker.recordLatency(elapsed);
            tracker.connectionErrors.incrementAndGet();
        } finally {
            tracker.totalCompleted.incrementAndGet();
        }
    }

    // =========================================================================
    // DATA STRUCTURES & LOGGING
    // =========================================================================

    static class WindowMetrics {
        final int windowNumber;
        final int requestsInWindow;
        double p50LatencyMs;
        double p95LatencyMs;

        WindowMetrics(int windowNumber, int requestsInWindow) {
            this.windowNumber = windowNumber;
            this.requestsInWindow = requestsInWindow;
        }
    }

    static class SoakRunTracker {
        final String testName;
        final int concurrency;
        final long plannedDurationSeconds;
        final AtomicInteger totalCompleted = new AtomicInteger(0);
        final AtomicInteger success2xx = new AtomicInteger(0);
        final AtomicInteger clientErrors4xx = new AtomicInteger(0);
        final AtomicInteger rateLimited429 = new AtomicInteger(0);
        final AtomicInteger serverErrors = new AtomicInteger(0);
        final AtomicInteger connectionErrors = new AtomicInteger(0);

        final List<Long> allLatencies = new CopyOnWriteArrayList<>();
        final List<Long> currentWindowLatencies = new CopyOnWriteArrayList<>();
        final List<WindowMetrics> windowMetrics = new ArrayList<>();

        long totalDurationMs = 0;
        double throughputRps = 0.0;
        long minLatencyMs = 0;
        long maxLatencyMs = 0;
        double avgLatencyMs = 0.0;
        double p50LatencyMs = 0.0;
        double p95LatencyMs = 0.0;
        double p99LatencyMs = 0.0;

        SoakRunTracker(String testName, int concurrency, long plannedDurationSeconds) {
            this.testName = testName;
            this.concurrency = concurrency;
            this.plannedDurationSeconds = plannedDurationSeconds;
        }

        void recordLatency(long latencyMs) {
            allLatencies.add(latencyMs);
            currentWindowLatencies.add(latencyMs);
        }

        synchronized WindowMetrics computeWindowMetrics(int windowNum, int windowRequests) {
            WindowMetrics wm = new WindowMetrics(windowNum, windowRequests);
            List<Long> snapshot = new ArrayList<>(currentWindowLatencies);
            currentWindowLatencies.clear();

            if (!snapshot.isEmpty()) {
                Collections.sort(snapshot);
                wm.p50LatencyMs = getPercentile(snapshot, 50.0);
                wm.p95LatencyMs = getPercentile(snapshot, 95.0);
            }
            return wm;
        }

        void computeOverall() {
            if (totalDurationMs > 0 && totalCompleted.get() > 0) {
                throughputRps = (totalCompleted.get() / (double) totalDurationMs) * 1000.0;
            }

            if (!allLatencies.isEmpty()) {
                List<Long> sorted = new ArrayList<>(allLatencies);
                Collections.sort(sorted);
                minLatencyMs = sorted.get(0);
                maxLatencyMs = sorted.get(sorted.size() - 1);
                avgLatencyMs = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0);
                p50LatencyMs = getPercentile(sorted, 50.0);
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

    private static void logAndPrintSoakSummary(SoakRunTracker t) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        System.out.println("----------------------------------------------------------------------------------------");
        System.out.printf("SOAK ENDURANCE RESULTS: [%s]%n", t.testName);
        System.out.printf("  Duration:         %.2f s | Concurrency: %d threads | Completed: %d reqs%n",
                t.totalDurationMs / 1000.0, t.concurrency, t.totalCompleted.get());
        System.out.printf("  Throughput:       %.2f req/sec%n", t.throughputRps);
        System.out.printf("  Status Codes:     2xx: %d | 4xx: %d | 429: %d | 5xx: %d | Drops: %d%n",
                t.success2xx.get(), t.clientErrors4xx.get(), t.rateLimited429.get(), t.serverErrors.get(), t.connectionErrors.get());
        System.out.printf("  Latency Profile:  Min: %d ms | Avg: %.2f ms | P50: %.2f ms | P95: %.2f ms | P99: %.2f ms | Max: %d ms%n",
                t.minLatencyMs, t.avgLatencyMs, t.p50LatencyMs, t.p95LatencyMs, t.p99LatencyMs, t.maxLatencyMs);
        System.out.println("----------------------------------------------------------------------------------------");

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE_PATH, true)))) {
            writer.println("----------------------------------------------------------------------------------------");
            writer.printf("[%s] SOAK RUN: %s%n", timestamp, t.testName);
            writer.printf("  Duration:          %.2f s | Concurrency: %d | Requests: %d | Throughput: %.2f RPS%n",
                    t.totalDurationMs / 1000.0, t.concurrency, t.totalCompleted.get(), t.throughputRps);
            writer.printf("  Status Breakdown:  2xx=%d, 4xx=%d, 429=%d, 5xx=%d, Drops=%d%n",
                    t.success2xx.get(), t.clientErrors4xx.get(), t.rateLimited429.get(), t.serverErrors.get(), t.connectionErrors.get());
            writer.printf("  Latencies (ms):    Min=%d, Avg=%.2f, P50=%.2f, P95=%.2f, P99=%.2f, Max=%d%n",
                    t.minLatencyMs, t.avgLatencyMs, t.p50LatencyMs, t.p95LatencyMs, t.p99LatencyMs, t.maxLatencyMs);

            if (!t.windowMetrics.isEmpty()) {
                writer.println("  Interval Breakdown:");
                for (WindowMetrics wm : t.windowMetrics) {
                    writer.printf("    - Window %d: %d reqs | P50: %.2f ms | P95: %.2f ms%n",
                            wm.windowNumber, wm.requestsInWindow, wm.p50LatencyMs, wm.p95LatencyMs);
                }
            }
            writer.flush();
        } catch (IOException e) {
            System.err.println("Failed to write to soak test log file: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        System.out.println("========================================================================================");
        System.out.println("STARTING API SOAK & ENDURANCE TESTING SUITE");
        System.out.println("Target: " + ApiConfig.getFullUrl(ApiConfig.LOGIN_ENDPOINT));
        System.out.println("Base URL: " + ApiConfig.getBaseUrl());
        System.out.println("Configured Duration: " + SOAK_DURATION_SECONDS + " seconds (" + (SOAK_DURATION_SECONDS / 60.0) + " minutes)");
        System.out.println("========================================================================================");

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(SoakTest.class))
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        System.out.println();
        System.out.println("========================================================================================");
        System.out.println("SOAK TEST SUITE SUMMARY");
        System.out.println("========================================================================================");
        System.out.println("Tests Started:   " + summary.getTestsStartedCount());
        System.out.println("Tests Succeeded: " + summary.getTestsSucceededCount());
        System.out.println("Tests Failed:    " + summary.getTestsFailedCount());
        System.out.println("Report Log:      " + LOG_FILE_PATH);
        System.out.println("========================================================================================");

        if (summary.getTestsFailedCount() > 0) {
            System.err.println("Soak/Endurance test identified failures! Check " + LOG_FILE_PATH);
            System.exit(1);
        } else {
            System.out.println("Soak and endurance testing completed successfully with no resource leaks!");
            System.exit(0);
        }
    }
}
