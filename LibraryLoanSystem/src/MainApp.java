import java.sql.SQLException;
import java.util.Scanner;


public class MainApp {

    private static ConnectionManager    cm;
    private static TransactionService   tx;
    private static BusinessLogic        bl;
    private static PerfomanceEvaluator pe;
    private static Scanner              sc;

    public static void main(String[] args) {

        cm = new ConnectionManager();
        tx = new TransactionService(cm);
        bl = new BusinessLogic(cm, tx);
        pe = new PerfomanceEvaluator(cm);
        sc = new Scanner(System.in);

        // Register Derby shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[App] Shutting down...");
            cm.shutdown();
        }));

        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║   Library Loan Management System (Derby)   ║");
        System.out.println("╚════════════════════════════════════════════╝");

        // Initialize DB
        try {
            cm.initializeDatabase();
        } catch (SQLException e) {
            System.err.println("[Fatal] Database initialization failed: " + e.getMessage());
            System.exit(1);
        }

        // Main loop
        boolean running = true;
        while (running) {
            printMenu();
            String choice = sc.nextLine().trim();
            switch (choice) {

                // ── Members ──────────────────────────────────────────────
                case "1" -> {
                    System.out.print("  Member name  : ");
                    String name = sc.nextLine().trim();
                    System.out.print("  Member email : ");
                    String email = sc.nextLine().trim();
                    int id = bl.registerMember(name, email);
                    if (id > 0) System.out.println("  ✔  Registered with MemberID = " + id);
                }
                case "2" -> bl.listMembers();

                // ── Books ─────────────────────────────────────────────────
                case "3" -> {
                    System.out.print("  Title  : ");
                    String title = sc.nextLine().trim();
                    System.out.print("  Author : ");
                    String author = sc.nextLine().trim();
                    System.out.print("  ISBN   : ");
                    String isbn = sc.nextLine().trim();
                    int id = bl.addBook(title, author, isbn);
                    if (id > 0) System.out.println("  ✔  Added with BookID = " + id);
                }
                case "4" -> bl.listBooks();
                case "5" -> {
                    System.out.print("  ISBN : ");
                    bl.findBookByISBN(sc.nextLine().trim());
                }

                // ── Loans ─────────────────────────────────────────────────
                case "6" -> {
                    int bookId   = readInt("  BookID   : ");
                    int memberId = readInt("  MemberID : ");
                    int loanId = bl.processLoan(bookId, memberId);
                    if (loanId > 0) System.out.println("  ✔  Loan created with LoanID = " + loanId);
                }
                case "7" -> {
                    int loanId = readInt("  LoanID : ");
                    if (bl.returnBook(loanId)) System.out.println("  ✔  Book returned successfully.");
                }
                case "8"  -> bl.listActiveLoans();
                case "9"  -> {
                    int memberId = readInt("  MemberID : ");
                    bl.listLoansByMember(memberId);
                }
                case "10" -> bl.listOverdueLoans();

                // ── Benchmarks ────────────────────────────────────────────
                case "11" -> {
                    System.out.println("\n  Starting benchmarks — this may take a minute...");
                    pe.runAllBenchmarks();
                }

                // ── Exit ──────────────────────────────────────────────────
                case "0" -> running = false;

                default  -> System.out.println("  [!] Unknown option. Please try again.");
            }
        }

        System.out.println("  Goodbye!");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static void printMenu() {
        System.out.println("""

            ┌─────────────────────────────────────┐
            │           MAIN MENU                 │
            ├─────────────────────────────────────┤
            │  Members                            │
            │   1. Register member                │
            │   2. List all members               │
            │  Books                              │
            │   3. Add book                       │
            │   4. List all books                 │
            │   5. Find book by ISBN              │
            │  Loans                              │
            │   6. Process loan                   │
            │   7. Return book                    │
            │   8. List all active loans          │
            │   9. Loans by member                │
            │  10. Overdue loans                  │
            │  Performance                        │
            │  11. Run JDBC benchmarks            │
            │   0. Exit                           │
            └─────────────────────────────────────┘
            Choice: """);
    }

    private static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            String line = sc.nextLine().trim();
            try {
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("  [!] Please enter a valid integer.");
            }
        }
    }
}