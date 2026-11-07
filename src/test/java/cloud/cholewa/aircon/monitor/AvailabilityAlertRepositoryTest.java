package cloud.cholewa.aircon.monitor;

import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Alert;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

//The open alerts (HAS-383, V4) on the tables the migrations create, in a PostgreSQL of Testcontainers - the setup of
//AirconStateRepositoryTest
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(AvailabilityAlertRepository.class)
class AvailabilityAlertRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 7, 15, 30, 0, 123456000);
    private static final Alert OFFICE = new Alert(140, "Air conditioner", "office", AT.minusMinutes(6), AT);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private AvailabilityAlertRepository sut;

    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void forgetTheAlerts() {
        databaseClient.sql("DELETE FROM aircon_availability_alert").then().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_an_alert_once_and_read_it_back() {
        sut.insert(OFFICE).as(StepVerifier::create).verifyComplete();
        sut.insert(new Alert(140, "Air conditioner", "office", AT, AT.plusMinutes(1))).as(StepVerifier::create)
            .verifyComplete();

        sut.findAll().as(StepVerifier::create).expectNext(OFFICE).verifyComplete();
    }

    @Test
    void should_move_the_last_alert_of_a_reminder() {
        sut.insert(OFFICE).as(StepVerifier::create).verifyComplete();

        sut.reminded(140, AT.plusHours(1)).as(StepVerifier::create).verifyComplete();

        sut.findAll().as(StepVerifier::create)
            .expectNext(new Alert(140, "Air conditioner", "office", AT.minusMinutes(6), AT.plusHours(1)))
            .verifyComplete();
    }

    @Test
    void should_close_an_alert() {
        sut.insert(OFFICE).as(StepVerifier::create).verifyComplete();

        sut.delete(140).as(StepVerifier::create).verifyComplete();

        sut.findAll().as(StepVerifier::create).verifyComplete();
    }
}
