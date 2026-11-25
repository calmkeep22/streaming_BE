package com.example.streaming;

import com.example.streaming.Entity.*;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SeatPricingMigrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("streaming").withUsername("test").withPassword("test");

    @Test
    void upgradesLegacyRowsCanBeRetriedAndMatchesHibernateSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "test", "test");
             Statement statement = connection.createStatement()) {
            // Start with the real bootstrap schema, then remove the three additions to reproduce the deployed schema.
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/backend/init/001-schema.sql"));
            statement.execute("ALTER TABLE seat_entity DROP COLUMN grade, DROP COLUMN price");
            statement.execute("ALTER TABLE payments DROP COLUMN seat_grade");
            statement.execute("INSERT INTO users(email,password,role) VALUES ('old@test','hash','USER')");
            statement.execute("INSERT INTO event_entity(title,start_time) VALUES ('old event','2099-01-01')");
            statement.execute("INSERT INTO seat_entity(event_id,seat_number,version) VALUES (1,7,0)");
            statement.execute("INSERT INTO payments(order_id,user_id,seat_id,amount,currency,status,provider,requested_at) "
                    + "VALUES ('old-order',1,1,70000,'KRW','SUCCESS','FAKE',NOW())");

            FileSystemResource migration = new FileSystemResource("deploy/backend/migrations/002-seat-grade-pricing.sql");
            // A previous interrupted attempt may already have added grade.
            statement.execute("ALTER TABLE seat_entity ADD COLUMN grade "
                    + "ENUM('FREE','STANDARD','PREMIUM','VIP') NOT NULL DEFAULT 'STANDARD'");
            ScriptUtils.executeSqlScript(connection, migration);
            try (var row = statement.executeQuery("SELECT grade,price,seat_number,version FROM seat_entity WHERE id=1")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("STANDARD");
                assertThat(row.getLong(2)).isEqualTo(50000);
                assertThat(row.getInt(3)).isEqualTo(7);
                assertThat(row.getLong(4)).isZero();
            }
            statement.execute("UPDATE seat_entity SET grade='VIP',price=10000 WHERE id=1");
            ScriptUtils.executeSqlScript(connection, migration);
            try (var row = statement.executeQuery("SELECT grade,price FROM seat_entity WHERE id=1")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("VIP");
                assertThat(row.getLong(2)).isEqualTo(10000);
            }
            try (var row = statement.executeQuery("SELECT amount,seat_grade,status FROM payments WHERE order_id='old-order'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(70000);
                assertThat(row.getString(2)).isEqualTo("STANDARD");
                assertThat(row.getString(3)).isEqualTo("SUCCESS");
            }
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", mysql.getJdbcUrl())
                .applySetting("hibernate.connection.username", "test")
                .applySetting("hibernate.connection.password", "test")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try {
            try (var factory = new MetadataSources(registry).addAnnotatedClass(UserEntity.class)
                    .addAnnotatedClass(EventEntity.class).addAnnotatedClass(SeatEntity.class)
                    .addAnnotatedClass(ReservationEntity.class).addAnnotatedClass(PaymentEntity.class)
                    .buildMetadata().buildSessionFactory()) {
                assertThat(factory.isOpen()).isTrue();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
