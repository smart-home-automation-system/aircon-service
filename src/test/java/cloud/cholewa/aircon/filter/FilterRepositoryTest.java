package cloud.cholewa.aircon.filter;

import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

//The run hours and the filters (HAS-391, V9 and the history of V2) on the tables the migrations create, in a PostgreSQL
//of Testcontainers - LEAD and ON CONFLICT are PostgreSQL's; the setup of AirconStateRepositoryTest
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(FilterRepository.class)
class FilterRepositoryTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 1, 8, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private FilterRepository repository;

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
    void forgetEverything() {
        databaseClient.sql("DELETE FROM aircon_state_change").then().as(StepVerifier::create).verifyComplete();
        databaseClient.sql("DELETE FROM aircon_filter").then().as(StepVerifier::create).verifyComplete();
    }

    //on 08:00-10:00 and 12:00 until now (14:00): 4 h in all; another change while on (the target) changes nothing
    @Test
    void should_count_the_time_a_unit_was_on() {
        change(140, T0, true);
        change(140, T0.plusHours(1), true);
        change(140, T0.plusHours(2), false);
        change(140, T0.plusHours(4), true);
        change(141, T0, true);

        repository.hours(140, null, T0.plusHours(6)).as(StepVerifier::create)
            .assertNext(hours -> assertThat(hours).isCloseTo(4.0, within(0.001)))
            .verifyComplete();
    }

    //since the cleaning at 09:30: the run that began before it counted from it - 0.5 + 2 h
    @Test
    void should_count_from_the_cleaning_on() {
        change(140, T0, true);
        change(140, T0.plusHours(2), false);
        change(140, T0.plusHours(4), true);

        repository.hours(140, T0.plusMinutes(90), T0.plusHours(6)).as(StepVerifier::create)
            .assertNext(hours -> assertThat(hours).isCloseTo(2.5, within(0.001)))
            .verifyComplete();
    }

    @Test
    void should_count_nothing_for_a_unit_never_on() {
        change(140, T0, false);

        repository.hours(140, null, T0.plusHours(6)).as(StepVerifier::create).expectNext(0.0).verifyComplete();
        repository.hours(142, null, T0.plusHours(6)).as(StepVerifier::create).expectNext(0.0).verifyComplete();
    }

    //a reminder before any cleaning, then a cleaning: the step forgotten
    @Test
    void should_store_the_reminders_and_the_cleanings() {
        repository.reminded(140, 200.0).as(StepVerifier::create).verifyComplete();
        repository.filters().as(StepVerifier::create).expectNext(new FilterRecord(140, null, 200.0)).verifyComplete();

        repository.cleaned(140, T0).as(StepVerifier::create).verifyComplete();
        repository.reminded(141, 250.0).as(StepVerifier::create).verifyComplete();
        repository.filters().collectList().as(StepVerifier::create)
            .assertNext(filters -> assertThat(filters).containsExactlyInAnyOrder(new FilterRecord(140, T0, null),
                new FilterRecord(141, null, 250.0)))
            .verifyComplete();
    }

    @Test
    void should_answer_the_runs_of_a_unit_in_a_range() {
        change(140, T0, true);
        change(140, T0.plusHours(2), false);
        change(140, T0.plusDays(2), true);

        repository.runs(140, T0, T0.plusDays(1)).collectList().as(StepVerifier::create)
            .assertNext(runs -> assertThat(runs).containsExactly(
                new UnitRun(T0, true, Mode.HEAT, 21, FanSpeed.AUTO, ChangeSource.RULE),
                new UnitRun(T0.plusHours(2), false, Mode.HEAT, 21, FanSpeed.AUTO, ChangeSource.RULE)))
            .verifyComplete();
    }

    private void change(final long unitId, final LocalDateTime at, final boolean power) {
        databaseClient.sql("""
                INSERT INTO aircon_state_change (unit_id, changed_at, power, mode, target_temperature, fan_speed, source)
                VALUES (:unitId, :at, :power, 'heat', 21, 'auto', 'rule')""")
            .bind("unitId", unitId).bind("at", at).bind("power", power)
            .then().as(StepVerifier::create).verifyComplete();
    }
}
