import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles all query-oriented business operations:
 * member registration, book addition, and read-only queries.
 * All write operations that touch multiple tables delegate to TransactionService.
 */
public class BusinessLogic {

    private final ConnectionManager cm;
    private final TransactionService tx;

    public BusinessLogic(ConnectionManager cm, TransactionService tx) {
        this.cm = cm;
        this.tx = tx;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Member management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Registers a new library member.
     *
     * @return generated MemberID, or -1 on failure
     */
    public int registerMember(String name, String email) {
        String sql = "INSERT INTO Members (Name, Email) VALUES (?, ?)";
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, name);
            ps.setString(2, email);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    System.out.printf("[BL] Member registered: %s (ID=%d)%n", name, id);
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[BL] registerMember failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Lists all members.
     */
    public void listMembers() {
        String sql = "SELECT MemberID, Name, Email, ActiveLoans, JoinDate FROM Members ORDER BY MemberID";
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            System.out.println("\n┌────┬──────────────────────┬─────────────────────────────┬──────┬────────────┐");
            System.out.println("│ ID │ Name                 │ Email                       │Loans │ JoinDate   │");
            System.out.println("├────┼──────────────────────┼─────────────────────────────┼──────┼────────────┤");
            while (rs.next()) {
                System.out.printf("│%3d │%-22s│%-29s│%6d│ %s │%n",
                        rs.getInt("MemberID"),
                        truncate(rs.getString("Name"), 22),
                        truncate(rs.getString("Email"), 29),
                        rs.getInt("ActiveLoans"),
                        rs.getDate("JoinDate"));
            }
            System.out.println("└────┴──────────────────────┴─────────────────────────────┴──────┴────────────┘");

        } catch (SQLException e) {
            System.err.println("[BL] listMembers failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Book management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Adds a book to the catalog.
     *
     * @return generated BookID, or -1 on failure
     */
    public int addBook(String title, String author, String isbn) {
        String sql = "INSERT INTO Books (Title, Author, ISBN) VALUES (?, ?, ?)";
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, title);
            ps.setString(2, author);
            ps.setString(3, isbn);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    System.out.printf("[BL] Book added: '%s' (ID=%d)%n", title, id);
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[BL] addBook failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Lists all books with availability.
     */
    public void listBooks() {
        String sql = "SELECT BookID, Title, Author, ISBN, Available FROM Books ORDER BY BookID";
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            System.out.println("\n┌────┬────────────────────────────────┬──────────────────┬───────────────────┬─────────┐");
            System.out.println("│ ID │ Title                          │ Author           │ ISBN              │ Avail?  │");
            System.out.println("├────┼────────────────────────────────┼──────────────────┼───────────────────┼─────────┤");
            while (rs.next()) {
                System.out.printf("│%3d │%-32s│%-18s│%-19s│ %-7s │%n",
                        rs.getInt("BookID"),
                        truncate(rs.getString("Title"), 32),
                        truncate(rs.getString("Author"), 18),
                        rs.getString("ISBN"),
                        rs.getBoolean("Available") ? "YES" : "NO");
            }
            System.out.println("└────┴────────────────────────────────┴──────────────────┴───────────────────┴─────────┘");

        } catch (SQLException e) {
            System.err.println("[BL] listBooks failed: " + e.getMessage());
        }
    }

    /**
     * Finds a book by ISBN (uses the index on Books.ISBN).
     */
    public void findBookByISBN(String isbn) {
        String sql = "SELECT BookID, Title, Author, Available FROM Books WHERE ISBN = ?";
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, isbn);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.printf("[BL] Found: [%d] '%s' by %s — %s%n",
                            rs.getInt("BookID"),
                            rs.getString("Title"),
                            rs.getString("Author"),
                            rs.getBoolean("Available") ? "Available" : "On Loan");
                } else {
                    System.out.println("[BL] No book found with ISBN: " + isbn);
                }
            }
        } catch (SQLException e) {
            System.err.println("[BL] findBookByISBN failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Loan queries
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Lists all active (un-returned) loans.
     */
    public void listActiveLoans() {
        String sql = """
            SELECT l.LoanID, m.Name AS Member, b.Title AS Book, l.LoanDate
            FROM   Loans l
            JOIN   Members m ON m.MemberID = l.MemberID
            JOIN   Books   b ON b.BookID   = l.BookID
            WHERE  l.ReturnDate IS NULL
            ORDER  BY l.LoanDate
        """;
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            System.out.println("\n┌──────┬──────────────────────┬────────────────────────────────┬────────────┐");
            System.out.println("│LoanID│ Member               │ Book                           │ LoanDate   │");
            System.out.println("├──────┼──────────────────────┼────────────────────────────────┼────────────┤");
            int count = 0;
            while (rs.next()) {
                count++;
                System.out.printf("│%6d│%-22s│%-32s│ %s │%n",
                        rs.getInt("LoanID"),
                        truncate(rs.getString("Member"), 22),
                        truncate(rs.getString("Book"), 32),
                        rs.getDate("LoanDate"));
            }
            System.out.println("└──────┴──────────────────────┴────────────────────────────────┴────────────┘");
            System.out.println("  Total active loans: " + count);

        } catch (SQLException e) {
            System.err.println("[BL] listActiveLoans failed: " + e.getMessage());
        }
    }

    /**
     * Lists active loans for a specific member (uses index on Loans.MemberID).
     */
    public void listLoansByMember(int memberId) {
        String sql = """
            SELECT l.LoanID, b.Title, l.LoanDate
            FROM   Loans l
            JOIN   Books b ON b.BookID = l.BookID
            WHERE  l.MemberID = ? AND l.ReturnDate IS NULL
            ORDER  BY l.LoanDate
        """;
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\n  Active loans for member " + memberId + ":");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("    Loan #%d  '%s'  since %s%n",
                            rs.getInt("LoanID"),
                            rs.getString("Title"),
                            rs.getDate("LoanDate"));
                }
                if (!any) System.out.println("    (none)");
            }
        } catch (SQLException e) {
            System.err.println("[BL] listLoansByMember failed: " + e.getMessage());
        }
    }

    /**
     * Lists overdue loans (on loan for more than 14 days without return).
     * Uses index on Loans.ReturnDate.
     */
    public void listOverdueLoans() {
        String sql = """
            SELECT l.LoanID, m.Name, b.Title, l.LoanDate,
                   {fn TIMESTAMPDIFF(SQL_TSI_DAY, CAST(l.LoanDate AS TIMESTAMP), CURRENT_TIMESTAMP)} AS DaysOut
            FROM   Loans l
            JOIN   Members m ON m.MemberID = l.MemberID
            JOIN   Books   b ON b.BookID   = l.BookID
            WHERE  l.ReturnDate IS NULL
              AND  l.LoanDate < {fn TIMESTAMPADD(SQL_TSI_DAY, -14, CURRENT_DATE)}
            ORDER  BY l.LoanDate
        """;
        try (Connection conn = cm.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            System.out.println("\n  ⚠  Overdue loans (>14 days):");
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("    Loan #%d  %s  '%s'  since %s (%d days)%n",
                        rs.getInt("LoanID"),
                        rs.getString("Name"),
                        rs.getString("Title"),
                        rs.getDate("LoanDate"),
                        rs.getInt("DaysOut"));
            }
            if (!any) System.out.println("    No overdue loans.");

        } catch (SQLException e) {
            System.err.println("[BL] listOverdueLoans failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Delegate mutations to TransactionService
    // ──────────────────────────────────────────────────────────────────────────

    public int processLoan(int bookId, int memberId) {
        return tx.processLoan(bookId, memberId);
    }

    public boolean returnBook(int loanId) {
        return tx.returnBook(loanId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────────────────

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}