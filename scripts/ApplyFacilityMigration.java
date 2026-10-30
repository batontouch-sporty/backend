import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Adds the supplied facility catalog without inventing references for existing matches. */
class ApplyFacilityMigration {
    public static void main(String[] args) throws Exception {
        Class.forName("org.mariadb.jdbc.Driver");
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".env"))) { config.load(reader); }
        String url = System.getenv().getOrDefault("DB_URL", config.getProperty("DB_URL"));
        String username = System.getenv().getOrDefault("DB_USERNAME", config.getProperty("DB_USERNAME"));
        String password = System.getenv().getOrDefault("DB_PASSWORD", config.getProperty("DB_PASSWORD"));
        boolean apply = args.length == 1 && args[0].equals("--apply");
        try (Connection c = DriverManager.getConnection(url, username, password)) {
            if (!"sporty".equals(c.getCatalog())) throw new IllegalStateException("Expected sporty database");
            boolean catalogExists;
            try (var tables = c.getMetaData().getTables(c.getCatalog(), null, "service", new String[]{"TABLE"})) {
                catalogExists = tables.next();
            }
            String preflight = catalogExists
                    ? "SELECT m.id FROM `match` m LEFT JOIN service s ON s.id=m.service_id WHERE s.id IS NULL LIMIT 1"
                    : "SELECT id FROM `match` LIMIT 1";
            try (var statement = c.createStatement(); var rs = statement.executeQuery(preflight)) {
                if (rs.next()) throw new IllegalStateException("Existing matches need real service records before adding the foreign key. No schema/data changes made.");
            }
            if (apply) {
                String sql = Files.readString(Path.of("docs/sql/002_facility_catalog.sql")).replaceAll("(?m)^--.*$", "");
                for (String part : sql.split(";")) {
                    if (part.isBlank()) continue;
                    try (var statement = c.createStatement()) { statement.execute(part); }
                }
            }
            System.out.println(apply ? "Facility tables and match foreign key installed; no existing records changed."
                    : "Facility migration preflight passed; no changes made.");
        }
    }
}
