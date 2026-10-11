package com.darkrich.blog.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Scan the same migration classpath as production even when MySQL tests are skipped. */
class MigrationDiscoveryTest {
    @Test void discoversUniqueVersionsAndPreservesTheExistingArchitectureMigration() {
        MigrationInfo[] migrations = Flyway.configure()
                .dataSource("jdbc:h2:mem:migration-discovery-" + UUID.randomUUID(), "sa", "")
                .locations("classpath:db/migration").load().info().all();
        assertThat(Arrays.stream(migrations).map(m -> m.getVersion().toString()).toList())
                .doesNotHaveDuplicates().contains("1", "2", "3", "4", "5");
        assertThat(migrations).anyMatch(m -> m.getVersion().toString().equals("4")
                && m.getScript().equals("V4__architecture_category_icon.sql"));
        assertThat(migrations).anyMatch(m -> m.getVersion().toString().equals("5")
                && m.getScript().equals("V5__assistant_runs.sql"));
    }
}
