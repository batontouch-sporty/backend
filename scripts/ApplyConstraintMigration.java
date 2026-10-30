import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** One-time, non-destructive migration. Refuses dirty data instead of fixing/deleting it. */
class ApplyConstraintMigration {
    public static void main(String[] args) throws Exception {
        Class.forName("org.mariadb.jdbc.Driver");
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".env"))) { config.load(reader); }
        String url = System.getenv().getOrDefault("DB_URL", config.getProperty("DB_URL"));
        String username = System.getenv().getOrDefault("DB_USERNAME", config.getProperty("DB_USERNAME"));
        String password = System.getenv().getOrDefault("DB_PASSWORD", config.getProperty("DB_PASSWORD"));
        String sql = Files.readString(Path.of("docs/sql/001_match_constraints.sql"))
                .replaceAll("(?m)^--.*$", "");
        boolean apply = args.length == 1 && args[0].equals("--apply");
        try (Connection c = DriverManager.getConnection(url, username, password)) {
            if (!"sporty".equals(c.getCatalog())) throw new IllegalStateException("Expected sporty database");
            for (String part : sql.split(";")) {
                String statement = part.trim();
                if (statement.isEmpty()) continue;
                if (statement.startsWith("SELECT")) {
                    try (var s = c.createStatement(); var rs = s.executeQuery(statement)) {
                        if (rs.next()) throw new IllegalStateException("Preflight failed; inspect and correct existing data manually before applying migration.");
                    }
                } else if (apply) {
                    try (var s = c.createStatement()) { s.execute(statement); }
                }
            }
            System.out.println(apply ? "Constraint migration applied; no records deleted." : "Preflight passed; no schema changes made. Use --apply to migrate.");
        }
    }
}
