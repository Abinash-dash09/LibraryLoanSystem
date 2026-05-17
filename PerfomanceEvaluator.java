import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Benchmarks four JDBC access patterns and prints a structured report.
 *
 * Test suites:
 *  1. Insert Strategy  – individual vs. batch inserts (1 000 / 10 000 rows)
 *  2. Query Strategy   – full-table scan vs. indexed lookup on Loans
 *  3. Statement Type   – Statement (string concat) vs. PreparedStatement
 *  4. TX Granularity   – per-operation commit vs. batched commit (100 ops)
 */
public class PerfomanceEvaluator {

    private static final int RUNS        = 5;
    private static final int SMALL_BATCH = 1_000;
    private static final int LARGE_BATCH = 10_000;
    private static final int WARM_LOOPS  = 50;

    private final ConnectionManager cm;

    /** Holds one benchmark result row. */
    private record BenchResult(String operation, int records, double avgMs, double stdDev, double throughput, String notes) {}

    public PerfomanceEvaluator(ConnectionManager cm) {
        this.cm = cm;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ──────────────────────────────────────────────────────────────────────────

    public void runAllBenchmarks() {
        System.out.println("\n========================================");
        System.out.println("  JDBC Performance Evaluation Report");
        System.out.println("========================================\n");

        List<BenchResult> results = new ArrayList<>();

        results.addAll(benchmarkInsertStrategies());
        results.addAll(benchmarkQueryStrategies());
        results.addAll(benchmarkStatementTypes());
        results.addAll(benchmarkTransactionGranularity());

        printReport(results);
        cleanBenchmarkData();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Suite 1 – Insert strategies
    // ──────────────────────────────────────────────────────────────────────────

    private List<BenchResult> benchmarkInsertStrategies() {
        System.out.println("[Benchmark] Suite 1: Insert Strategies...");
        List<BenchResult> results = new ArrayList<>();

        for (int n : new int[]{SMALL_BATCH, LARGE_BATCH}) {

            // Warm-up
            warmUp();
            cleanBenchmarkData();

            // Individual inserts
            double[] times = new double[RUNS];
            for (int r = 0; r < RUNS; r++) {
                cleanBenchmarkData();
                long t0 = System.nanoTime();
                individualInserts(n);
                times[r] = nanoToMs(System.nanoTime() - t0);
            }
            results.add(toResult("Individual INSERT", n, times,
                    "executeUpdate() per row; 1 TX per insert"));

            warmUp();
            cleanBenchmarkData();

            // Batch inserts
            for (int r = 0; r < RUNS; r++) {
                cleanBenchmarkData();
                long t0 = System.nanoTime();
                batchInserts(n);
                times[r] = nanoToMs(System.nanoTime() - t0);
            }
            results.add(toResult("Batch INSERT", n, times,
                    "addBatch() + executeBatch(); single TX"));
        }

        cleanBenchmarkData();
        System.out.println("[Benchmark] Suite 1 complete.\n");
        return results;
    }

    private void individualInserts(int count) {
        String sql = "INSERT INTO Members (Name, Email) VALUES (?, ?)";
        try (Connection conn = cm.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < count; i++) {
                    ps.setString(1, "BenchMember" + i);
                    ps.setString(2, "bench" + i + "@perf.test");
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[Bench] individualInserts error: " + e.getMessage());
        }
    }

    private void batchInserts(int count) {
        String sql = "INSERT INTO Members (Name, Email) VALUES (?, ?)";
        try (Connection conn = cm.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < count; i++) {
                    ps.setString(1, "BenchMember" + i);
                    ps.setString(2, "bench" + i + "@perf.test");
                    ps.addBatch();
                    if ((i + 1) % 500 == 0) ps.executeBatch(); // flush every 500
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[Bench] batchInserts error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Suite 2 – Query strategies
    // ──────────────────────────────────────────────────────────────────────────

    private List<BenchResult> benchmarkQueryStrategies() {
        System.out.println("[Benchmark] Suite 2: Query Strategies...");

        // Seed some Loans rows for a realistic scan
        seedLoansForQuery();

        List<BenchResult> results = new ArrayList<>();
        double[] times = new double[RUNS];

        warmUp();

        // Full-table scan (no WHERE clause uses index)
        for (int r = 0; r < RUNS; r++) {
            long t0 = System.nanoTime();
            fullTableScan();
            times[r] = nanoToMs(System.nanoTime() - t0);
        }
        results.add(toResult("Full-table scan (Loans)", 0, times,
                "SELECT * FROM Loans — no index hint"));

        warmUp();

        // Indexed lookup on MemberID
        for (int r = 0; r < RUNS; r++) {
            long t0 = System.nanoTime();
            indexedLookup(1);
            times[r] = nanoToMs(System.nanoTime() - t0);
        }
        results.add(toResult("Indexed lookup (Loans.MemberID)", 0, times,
                "WHERE MemberID=? — uses idx_loans_member"));

        System.out.println("[Benchmark] Suite 2 complete.\n");
        return results;
    }

    private void fullTableScan() {
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM Loans");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) { /* consume */ }
        } catch (SQLException e) {
            System.err.println("[Bench] fullTableScan error: " + e.getMessage());
        }
    }

    private void indexedLookup(int memberId) {
        String sql = "SELECT * FROM Loans WHERE MemberID = ?";
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { /* consume */ }
            }
        } catch (SQLException e) {
            System.err.println("[Bench] indexedLookup error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Suite 3 – Statement vs PreparedStatement
    // ──────────────────────────────────────────────────────────────────────────

    private List<BenchResult> benchmarkStatementTypes() {
        System.out.println("[Benchmark] Suite 3: Statement Type Comparison...");
        List<BenchResult> results = new ArrayList<>();
        int queries = 500;
        double[] times = new double[RUNS];

        warmUp();

        // Raw Statement (string concatenation)
        for (int r = 0; r < RUNS; r++) {
            long t0 = System.nanoTime();
            rawStatementQueries(queries);
            times[r] = nanoToMs(System.nanoTime() - t0);
        }
        results.add(toResult("Raw Statement (concat)", queries, times,
                "New parse+plan per execution; SQL injection risk"));

        warmUp();

        // PreparedStatement (pre-compiled)
        for (int r = 0; r < RUNS; r++) {
            long t0 = System.nanoTime();
            preparedStatementQueries(queries);
            times[r] = nanoToMs(System.nanoTime() - t0);
        }
        results.add(toResult("PreparedStatement (cached)", queries, times,
                "Plan compiled once; reused 500×"));

        System.out.println("[Benchmark] Suite 3 complete.\n");
        return results;
    }

    @SuppressWarnings("SqlSourceToSinkFlow")
    private void rawStatementQueries(int count) {
        try (Connection conn = cm.getConnection();
             Statement stmt = conn.createStatement()) {
            for (int i = 1; i <= count; i++) {
                // Intentionally using string concat to simulate un-parameterized queries
                String sql = "SELECT * FROM Books WHERE BookID = " + (i % 8 + 1);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) { /* consume */ }
                }
            }
        } catch (SQLException e) {
            System.err.println("[Bench] rawStatementQueries error: " + e.getMessage());
        }
    }

    private void preparedStatementQueries(int count) {
        String sql = "SELECT * FROM Books WHERE BookID = ?";
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= count; i++) {
                ps.setInt(1, i % 8 + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* consume */ }
                }
            }
        } catch (SQLException e) {
            System.err.println("[Bench] preparedStatementQueries error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Suite 4 – Transaction granularity
    // ──────────────────────────────────────────────────────────────────────────

    private List<BenchResult> benchmarkTransactionGranularity() {
        System.out.println("[Benchmark] Suite 4: Transaction Granularity...");
        List<BenchResult> results = new ArrayList<>();
        int ops = 100;
        double[] times = new double[RUNS];

        warmUp();
        cleanBenchmarkData();

        // Per-operation commit
        for (int r = 0; r < RUNS; r++) {
            cleanBenchmarkData();
            long t0 = System.nanoTime();
            perOperationCommit(ops);
            times[r] = nanoToMs(System.nanoTime() - t0);
        }
        results.add(toResult("Per-op commit (100 inserts)", ops, times,
                "commit() after each INSERT; 100 fsync calls"));

        warmUp();
        cleanBenchmarkData();

        // Batched commit
        for (int r = 0; r < RUNS; r++) {
            cleanBenchmarkData();
            long t0 = System.nanoTime();
            batchedCommit(ops);
            times[r] = nanoToMs(System.nanoTime() - t0);
        }
        results.add(toResult("Batched commit (100 inserts)", ops, times,
                "Single commit() after all 100 INSERTs"));

        cleanBenchmarkData();
        System.out.println("[Benchmark] Suite 4 complete.\n");
        return results;
    }

    private void perOperationCommit(int count) {
        String sql = "INSERT INTO Members (Name, Email) VALUES (?, ?)";
        try (Connection conn = cm.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < count; i++) {
                    ps.setString(1, "TxMember" + i);
                    ps.setString(2, "tx" + i + "@perf.test");
                    ps.executeUpdate();
                    conn.commit(); // one commit per row
                }
            } catch (SQLException e) {
                conn.rollback();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[Bench] perOperationCommit error: " + e.getMessage());
        }
    }

    private void batchedCommit(int count) {
        String sql = "INSERT INTO Members (Name, Email) VALUES (?, ?)";
        try (Connection conn = cm.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < count; i++) {
                    ps.setString(1, "TxMember" + i);
                    ps.setString(2, "tx" + i + "@perf.test");
                    ps.executeUpdate();
                }
                conn.commit(); // single commit for all rows
            } catch (SQLException e) {
                conn.rollback();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[Bench] batchedCommit error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void warmUp() {
        for (int i = 0; i < WARM_LOOPS; i++) {
            try (Connection conn = cm.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM Members");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
            } catch (SQLException ignored) {}
        }
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
    }

    /**
     * Removes all benchmark-inserted Members (those with emails ending @perf.test).
     */
    private void cleanBenchmarkData() {
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM Members WHERE Email LIKE '%@perf.test'")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[Bench] cleanBenchmarkData error: " + e.getMessage());
        }
    }

    private void seedLoansForQuery() {
        // Ensure a handful of loan rows exist for scan tests
        try (Connection conn = cm.getConnection()) {
            conn.setAutoCommit(false);
            String sql = "INSERT INTO Loans (MemberID, BookID) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // We'll add loans only if Loans is empty
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM Loans")) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        conn.rollback();
                        conn.setAutoCommit(true);
                        return;
                    }
                }
                // Borrow all 8 seed books for member 1 (seeded members 1-5, books 1-8)
                for (int b = 1; b <= 8; b++) {
                    ps.setInt(1, 1);
                    ps.setInt(2, b);
                    ps.addBatch();
                }
                ps.executeBatch();
                // Mark those books unavailable
                try (PreparedStatement pu = conn.prepareStatement(
                        "UPDATE Books SET Available = FALSE WHERE BookID <= 8")) {
                    pu.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[Bench] seedLoansForQuery error: " + e.getMessage());
        }
    }

    private BenchResult toResult(String op, int records, double[] times, String notes) {
        double sum = 0, sumSq = 0;
        for (double t : times) { sum += t; sumSq += t * t; }
        double avg = sum / times.length;
        double variance = (sumSq / times.length) - (avg * avg);
        double std = Math.sqrt(Math.max(0, variance));
        double throughput = records > 0 ? records / (avg / 1000.0) : (1000.0 / avg);
        return new BenchResult(op, records, avg, std, throughput, notes);
    }

    private double nanoToMs(long nano) {
        return nano / 1_000_000.0;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Report rendering
    // ──────────────────────────────────────────────────────────────────────────

    private void printReport(List<BenchResult> results) {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                                   JDBC PERFORMANCE REPORT                                                  ║");
        System.out.println("╠══════════════════════════════════════════════════════╦════════╦══════════╦══════════╦═══════════╦═══════════════════════════════════╣");
        System.out.println("║ Operation                                            │Records │ Avg (ms) │ StdDev   │ Ops/sec   │ Notes                             ║");
        System.out.println("╠══════════════════════════════════════════════════════╬════════╬══════════╬══════════╬═══════════╬═══════════════════════════════════╣");

        for (BenchResult r : results) {
            System.out.printf("║ %-52s│%8s│%10.2f│%10.2f│%11.1f│ %-33s║%n",
                    truncate(r.operation(), 52),
                    r.records() == 0 ? "  N/A   " : String.format("%,8d", r.records()),
                    r.avgMs(),
                    r.stdDev(),
                    r.throughput(),
                    truncate(r.notes(), 33));
        }

        System.out.println("╚══════════════════════════════════════════════════════╩════════╩══════════╩══════════╩═══════════╩═══════════════════════════════════╝");
        System.out.println("  * Each test ran " + RUNS + " times; results show mean ± standard deviation.");
        System.out.println("  * Throughput: ops/sec for inserts/queries; ms per call for statement comparison.");
        System.out.println();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}