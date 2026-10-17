package com.example.verification;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the shipped flyway-migrations good examples against PostgreSQL in an isolated schema.
 * CREATE INDEX CONCURRENTLY needs its own migration file AND a session-level Flyway lock.
 */
@SpringBootTest(classes = FlywayConcurrentIndexTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FlywayConcurrentIndexTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {}

    private static final String SCHEMA = "flyway_examples_check";
    private static final String LEGACY_COLUMN = "ALTER TABLE orders ADD COLUMN full_name VARCHAR(255);\n";

    @Autowired DataSource dataSource;
    @TempDir Path migrations;

    @BeforeEach void resetSchema() {
        new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test void shippedExamplesApplyAndBuildValidConcurrentIndex() throws IOException {
        write("V1__init.sql", example("templates/V1__create_table_template.sql"));
        write("V2__legacy_full_name.sql", LEGACY_COLUMN);
        write("V4__add_shipping_address_to_orders.sql", example("examples/good-migration.sql"));
        write("V5__index_orders_shipping_address.sql", example("examples/good-migration-concurrent-index.sql"));

        assertEquals(4, flyway().migrate().migrationsExecuted);

        Boolean valid = new JdbcTemplate(dataSource).queryForObject(
                "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = ? AND c.relname = 'idx_orders_shipping_address'",
                Boolean.class, SCHEMA);
        assertEquals(Boolean.TRUE, valid);
    }

    @Test void concurrentIndexMixedIntoOneMigrationIsRejected() throws IOException {
        write("V1__init.sql", example("templates/V1__create_table_template.sql"));
        write("V2__legacy_full_name.sql", LEGACY_COLUMN);
        write("V4__mixed.sql", example("examples/good-migration.sql")
                + "\n" + example("examples/good-migration-concurrent-index.sql"));

        FlywayException error = assertThrows(FlywayException.class, () -> flyway().migrate());
        assertTrue(error.getMessage().contains("non-transactional"), error.getMessage());
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("filesystem:" + migrations)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load();
    }

    private void write(String name, String sql) throws IOException {
        Files.writeString(migrations.resolve(name), sql, StandardCharsets.UTF_8);
    }

    private static String example(String path) throws IOException {
        try (InputStream in = FlywayConcurrentIndexTest.class.getResourceAsStream("/flyway-examples/" + path)) {
            assertNotNull(in, "missing shipped example " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
