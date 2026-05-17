import java.sql.*;

/**
 * Manages Derby embedded database connections, schema creation, and lifecycle.
 */
public class ConnectionManager {

    private static final String DB_URL = "jdbc:derby:librarydb;create=true";
    private static final String SHUTDOWN_URL = "jdbc:derby:librarydb;shutdown=true";

    private Connection connection;

    /**
     * Opens a single shared connection and bootstraps the schema + seed data.
     */
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(DB_URL);
            connection.setAutoCommit(true); // default; TransactionService overrides per-operation
        }
        return connection;
    }

    /**
     * Creates tables, indexes, and seed data if they do not already exist.
     */
    public void initializeDatabase() throws SQLException {
        Connection conn = getConnection();

        try (Statement stmt = conn.createStatement()) {

            // ── Members ──────────────────────────────────────────────────
            if (!tableExists(conn, "MEMBERS")) {
                stmt.execute("""
                    CREATE TABLE Members (
                        MemberID    INTEGER       NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        Name        VARCHAR(100)  NOT NULL,
                        Email       VARCHAR(150)  NOT NULL UNIQUE,
                        ActiveLoans INTEGER       NOT NULL DEFAULT 0,
                        JoinDate    DATE          NOT NULL DEFAULT CURRENT_DATE
                    )
                """);
                System.out.println("[DB] Table 'Members' created.");
            }

            // ── Books ─────────────────────────────────────────────────────
            if (!tableExists(conn, "BOOKS")) {
                stmt.execute("""
                    CREATE TABLE Books (
                        BookID      INTEGER       NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        Title       VARCHAR(200)  NOT NULL,
                        Author      VARCHAR(100)  NOT NULL,
                        ISBN        VARCHAR(20)   NOT NULL UNIQUE,
                        Available   BOOLEAN       NOT NULL DEFAULT TRUE
                    )
                """);
                stmt.execute("CREATE INDEX idx_books_isbn ON Books(ISBN)");
                System.out.println("[DB] Table 'Books' created with ISBN index.");
            }

            // ── Loans ─────────────────────────────────────────────────────
            if (!tableExists(conn, "LOANS")) {
                stmt.execute("""
                    CREATE TABLE Loans (
                        LoanID      INTEGER  NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        MemberID    INTEGER  NOT NULL REFERENCES Members(MemberID),
                        BookID      INTEGER  NOT NULL REFERENCES Books(BookID),
                        LoanDate    DATE     NOT NULL DEFAULT CURRENT_DATE,
                        ReturnDate  DATE,
                        CONSTRAINT uq_active_loan UNIQUE (BookID, ReturnDate)
                    )
                """);
                stmt.execute("CREATE INDEX idx_loans_member   ON Loans(MemberID)");
                stmt.execute("CREATE INDEX idx_loans_return   ON Loans(ReturnDate)");
                System.out.println("[DB] Table 'Loans' created with indexes.");
            }
        }

        seedData(conn);
    }

    private void seedData(Connection conn) throws SQLException {
        // Only seed if Members is empty
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM Members")) {
            rs.next();
            if (rs.getInt(1) > 0) return;
        }

        System.out.println("[DB] Seeding baseline data...");

        try (PreparedStatement psMember = conn.prepareStatement(
                "INSERT INTO Members (Name, Email) VALUES (?, ?)");
             PreparedStatement psBook = conn.prepareStatement(
                "INSERT INTO Books (Title, Author, ISBN) VALUES (?, ?, ?)")) {

            // Members
            String[][] members = {
                {"Alice Johnson", "alice@example.com"},
                {"Bob Smith",     "bob@example.com"},
                {"Carol White",   "carol@example.com"},
                {"David Brown",   "david@example.com"},
                {"Eva Martinez",  "eva@example.com"}
            };
            for (String[] m : members) {
                psMember.setString(1, m[0]);
                psMember.setString(2, m[1]);
                psMember.executeUpdate();
            }

            // Books
            String[][] books = {
                {"Clean Code",                  "Robert C. Martin", "978-0132350884"},
                {"The Pragmatic Programmer",    "Hunt & Thomas",    "978-0201616224"},
                {"Design Patterns",             "Gang of Four",     "978-0201633610"},
                {"Effective Java",              "Joshua Bloch",     "978-0134685991"},
                {"Database System Concepts",    "Silberschatz",     "978-0078022159"},
                {"Introduction to Algorithms",  "CLRS",             "978-0262033848"},
                {"You Don't Know JS",           "Kyle Simpson",     "978-1491924464"},
                {"The Mythical Man-Month",      "Fred Brooks",      "978-0201835953"}
            };
            for (String[] b : books) {
                psBook.setString(1, b[0]);
                psBook.setString(2, b[1]);
                psBook.setString(3, b[2]);
                psBook.executeUpdate();
            }
        }
        System.out.println("[DB] Seed data inserted.");
    }

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, "APP", tableName.toUpperCase(), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /**
     * Cleanly shuts down Derby and releases file locks.
     */
    public void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}

        try {
            DriverManager.getConnection(SHUTDOWN_URL);
        } catch (SQLException e) {
            // Derby always throws XJ015 on clean shutdown — this is expected
            if ("XJ015".equals(e.getSQLState())) {
                System.out.println("[DB] Derby shut down cleanly.");
            } else {
                System.err.println("[DB] Shutdown warning: " + e.getMessage());
            }
        }
    }
}