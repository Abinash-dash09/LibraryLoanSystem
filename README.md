# LibraryLoanSystem

## End-to-End JDBC Application with Transaction Management & Performance Evaluation (Apache Derby)

## Project Overview
This is a console-based Library Loan Management System built using Java and Apache Derby database. It demonstrates real-world database programming including ACID transactions, error handling, and performance benchmarking.

## Technologies Used
- Java 21
- Apache Derby 10.16.1.1
- JDBC API
- Eclipse IDE

## Project Structure
LibraryLoanSystem/
├── src/
│   ├── MainApp.java
│   ├── ConnectionManager.java
│   ├── TransactionService.java
│   ├── BusinessLogic.java
│   └── PerformanceEvaluator.java
└── lib/
    ├── derby.jar
    ├── derbyshared.jar
    └── derbytools.jar

## Database Tables
- Members - Stores library member details
- Books - Stores book catalog information
- Loans - Stores borrowing records

## Features
- Register new library members
- Add books to catalog
- Search books by ISBN
- Process book loans with full ACID transaction support
- Return borrowed books
- View all active loans
- View loans by specific member
- Detect overdue books (more than 14 days)
- Run JDBC performance benchmarks
- Generate performance comparison reports

## Transaction Management
- Explicit commit and rollback on all multi-table operations
- Savepoint support for partial rollback
- PreparedStatement used for all queries
- Try-with-resources for automatic resource cleanup

## Performance Benchmarks
- Individual INSERT vs Batch INSERT (1000 and 10000 records)
- Full table scan vs Indexed lookup
- Raw Statement vs PreparedStatement
- Per-operation commit vs Batched commit

## How to Run in Eclipse
1. Clone or download this repository
2. Open Eclipse and import as Java Project
3. Create lib folder in project
4. Add derby.jar, derbyshared.jar, derbytools.jar to lib folder
5. Right click the 3 jar files
6. Click Build Path and Add to Build Path
7. Open MainApp.java
8. Right click and Run As Java Application
9. Use the console menu to interact with the system

## Pre-loaded Seed Data
Members: Alice Johnson, Bob Smith, Carol White, David Brown, Eva Martinez

Books:
- Clean Code by Robert C. Martin
- The Pragmatic Programmer by Hunt and Thomas
- Design Patterns by Gang of Four
- Effective Java by Joshua Bloch
- Database System Concepts by Silberschatz
- Introduction to Algorithms by CLRS
- You Don't Know JS by Kyle Simpson
- The Mythical Man-Month by Fred Brooks

## Menu Options
- 1. Register member
- 2. List all members
- 3. Add book
- 4. List all books
- 5. Find book by ISBN
- 6. Process loan
- 7. Return book
- 8. List all active loans
- 9. Loans by member
- 10. Overdue loans
- 11. Run JDBC benchmarks
- 0. Exit
