import java.sql.*;

/**
 * Encapsulates all explicit transaction management:
 *  - begin / commit / rollback
 *  - savepoint creation and partial rollback
 *  - ACID demonstration helpers
 */
public class TransactionService {

    private final ConnectionManager cm;

    public TransactionService(ConnectionManager cm) {
        this.cm = cm;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Core loan processing (multi-step transaction with savepoint)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Processes a book loan in a single ACID transaction.
     *
     * Steps:
     *  1. Verify book is available          (SELECT … FOR UPDATE)
     *  2. Mark book unavailable             (UPDATE Books)
     *  3. Insert loan record                (INSERT Loans)   ← savepoint here
     *  4. Increment member active loan count (UPDATE Members)
     *
     * If step 4 fails, only the loan INSERT is rolled back via savepoint.
     * If any earlier step fails, the entire transaction is rolled back.
     *
     * @return generated LoanID, or -1 on failure
     */
    public int processLoan(int bookId, int memberId) {
        Connection conn = null;
        Savepoint sp = null;
        int loanId = -1;

        try {
            conn = cm.getConnection();
            conn.setAutoCommit(false);

            // Step 1 – verify availability
            int available = checkBookAvailability(conn, bookId);
            if (available == 0) {
                System.out.println("[TX] Book " + bookId + " is not available.");
                conn.rollback();
                conn.setAutoCommit(true);
                return -1;
            }

            // Step 2 – mark book unavailable
            updateBookStatus(conn, bookId, false);

            // Step 3 – insert loan record; create savepoint just before
            sp = conn.setSavepoint("after_book_update");
            loanId = insertLoan(conn, bookId, memberId);

            // Step 4 – increment member loan count
            updateMemberLoanCount(conn, memberId, +1);

            conn.commit();
            System.out.printf("[TX] Loan #%d committed (Book %d → Member %d)%n",
                    loanId, bookId, memberId);

        } catch (SQLException e) {
            System.err.println("[TX] Error during processLoan: " + e.getMessage());
            if (conn != null) {
                try {
                    if (sp != null && isLoanInsertError(e)) {
                        // Partial rollback: undo only the loan insert, keep book-status update
                        conn.rollback(sp);
                        System.out.println("[TX] Partial rollback to savepoint 'after_book_update'.");
                        // Re-mark book as available since loan did not persist
                        try { updateBookStatus(conn, bookId, true); } catch (SQLException ignored) {}
                        conn.commit();
                    } else {
                        conn.rollback();
                        System.out.println("[TX] Full rollback executed.");
                    }
                } catch (SQLException re) {
                    System.err.println("[TX] Rollback failed: " + re.getMessage());
                }
            }
            loanId = -1;
        } finally {
            restoreAutoCommit(conn);
        }
        return loanId;
    }

    /**
     * Returns a book: marks it available, closes the loan, decrements member count.
     *
     * @return true on success
     */
    public boolean returnBook(int loanId) {
        Connection conn = null;
        try {
            conn = cm.getConnection();
            conn.setAutoCommit(false);

            // Fetch loan info
            int[] ids = getLoanInfo(conn, loanId);
            if (ids == null) {
                System.out.println("[TX] Loan #" + loanId + " not found or already returned.");
                conn.rollback();
                return false;
            }
            int bookId = ids[0], memberId = ids[1];

            // Close the loan
            closeLoan(conn, loanId);

            // Mark book available
            updateBookStatus(conn, bookId, true);

            // Decrement member loan count
            updateMemberLoanCount(conn, memberId, -1);

            conn.commit();
            System.out.printf("[TX] Return of loan #%d committed.%n", loanId);
            return true;

        } catch (SQLException e) {
            System.err.println("[TX] Error during returnBook: " + e.getMessage());
            rollbackQuietly(conn);
            return false;
        } finally {
            restoreAutoCommit(conn);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private SQL helpers
    // ──────────────────────────────────────────────────────────────────────────

    private int checkBookAvailability(Connection conn, int bookId) throws SQLException {
        String sql = "SELECT CAST(Available AS INTEGER) FROM Books WHERE BookID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Book " + bookId + " does not exist.");
            }
        }
    }

    private void updateBookStatus(Connection conn, int bookId, boolean available) throws SQLException {
        String sql = "UPDATE Books SET Available = ? WHERE BookID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, available);
            ps.setInt(2, bookId);
            ps.executeUpdate();
        }
    }

    private int insertLoan(Connection conn, int bookId, int memberId) throws SQLException {
        String sql = "INSERT INTO Loans (MemberID, BookID) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, memberId);
            ps.setInt(2, bookId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
            }
        }
        return -1;
    }

    private void updateMemberLoanCount(Connection conn, int memberId, int delta) throws SQLException {
        String sql = "UPDATE Members SET ActiveLoans = ActiveLoans + ? WHERE MemberID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setInt(2, memberId);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new SQLException("Member " + memberId + " not found.");
        }
    }

    private int[] getLoanInfo(Connection conn, int loanId) throws SQLException {
        String sql = "SELECT BookID, MemberID FROM Loans WHERE LoanID = ? AND ReturnDate IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new int[]{rs.getInt(1), rs.getInt(2)};
            }
        }
        return null;
    }

    private void closeLoan(Connection conn, int loanId) throws SQLException {
        String sql = "UPDATE Loans SET ReturnDate = CURRENT_DATE WHERE LoanID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, loanId);
            ps.executeUpdate();
        }
    }

    private boolean isLoanInsertError(SQLException e) {
        // Heuristic: constraint violation on Loans table
        return "23000".equals(e.getSQLState()) || "23505".equals(e.getSQLState());
    }

    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try { conn.rollback(); } catch (SQLException ignored) {}
        }
    }

    private void restoreAutoCommit(Connection conn) {
        if (conn != null) {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }
}