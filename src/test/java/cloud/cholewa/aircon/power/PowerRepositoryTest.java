package cloud.cholewa.aircon.power;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

//The draw of the plugs (HAS-390, V8) on the tables the migrations create, in a PostgreSQL of Testcontainers - the window
//functions and ON CONFLICT are PostgreSQL's; the setup of AirconStateRepositoryTest
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(PowerRepository.class)
class PowerRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);
    private static final LocalDateTime MIDNIGHT = DAY.atStartOfDay();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private PowerRepository repository;

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
        for (final String table : new String[]{"aircon_power_sample", "aircon_power_day", "aircon_power_cycle"}) {
            databaseClient.sql("DELETE FROM " + table).then().as(StepVerifier::create).verifyComplete();
        }
    }

    //the rise from the sample before the day counted with the first sample of the day, a reset (a fall) never counted:
    //1000 (before midnight) -> 2000 -> 2500 -> 100 (a reset) -> 600 = 1000 + 500 + 0 + 500 Wh
    @Test
    void should_count_the_rises_of_the_counter_and_never_a_reset() {
        sample(PlugName.INDOOR, MIDNIGHT.minusMinutes(1), 1000.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusMinutes(1), 2000.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusHours(1), 2500.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusHours(2), 100.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusHours(3), 600.0);
        sample(PlugName.SANCTUM, MIDNIGHT.plusHours(1), 9_000_000.0);

        repository.energy(PlugName.INDOOR, MIDNIGHT, MIDNIGHT.plusDays(1)).as(StepVerifier::create)
            .expectNext(Optional.of(2.0))
            .verifyComplete();
    }

    //one sample alone counts nothing: not known, never 0 kWh
    @Test
    void should_answer_no_energy_without_two_samples() {
        sample(PlugName.SANCTUM, MIDNIGHT.plusHours(1), 9_000_000.0);

        repository.energy(PlugName.SANCTUM, MIDNIGHT, MIDNIGHT.plusDays(1)).as(StepVerifier::create)
            .expectNext(Optional.empty())
            .verifyComplete();
    }

    //a day with samples closed once - a second close leaves it; a day without samples not closed
    @Test
    void should_close_a_day_once_and_only_a_day_with_samples() {
        sample(PlugName.INDOOR, MIDNIGHT.plusHours(1), 1000.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusHours(2), 1750.0);

        repository.closeDay(PlugName.INDOOR, DAY).as(StepVerifier::create).verifyComplete();
        sample(PlugName.INDOOR, MIDNIGHT.plusHours(3), 5000.0);
        repository.closeDay(PlugName.INDOOR, DAY).as(StepVerifier::create).verifyComplete();
        repository.closeDay(PlugName.INDOOR, DAY.minusDays(1)).as(StepVerifier::create).verifyComplete();

        repository.days(PlugName.INDOOR, DAY.minusDays(7), DAY.plusDays(1)).as(StepVerifier::create)
            .expectNext(Optional.of(0.75))
            .verifyComplete();
        repository.days(PlugName.INDOOR, DAY.minusDays(7), DAY).as(StepVerifier::create)
            .expectNext(Optional.empty())
            .verifyComplete();
    }

    //buckets of 5 minutes on the clock of the house, the average, the lowest and the highest
    @Test
    void should_answer_the_power_in_buckets() {
        sample(PlugName.INDOOR, MIDNIGHT.plusMinutes(1), 100.0, 600.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusMinutes(4), 101.0, 400.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusMinutes(6), 102.0, 10.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusMinutes(11), 103.0, 20.0);

        repository.buckets(PlugName.INDOOR, MIDNIGHT, MIDNIGHT.plusMinutes(10), 300).collectList()
            .as(StepVerifier::create)
            .assertNext(buckets -> assertThat(buckets).containsExactly(
                new PowerBucket(MIDNIGHT, 500.0, 400.0, 600.0),
                new PowerBucket(MIDNIGHT.plusMinutes(5), 10.0, 10.0, 10.0)))
            .verifyComplete();
    }

    @Test
    void should_store_a_cycle_and_answer_the_last_one() {
        repository.insertCycle(PlugName.INDOOR, MIDNIGHT, null).as(StepVerifier::create)
            .assertNext(id -> assertThat(id).isPositive()).verifyComplete();
        final long id = repository.insertCycle(PlugName.INDOOR, MIDNIGHT.plusHours(1), "Krzysztof").block();

        repository.runningCycles().collectList().as(StepVerifier::create)
            .assertNext(running -> assertThat(running).hasSize(2)).verifyComplete();
        repository.finishCycle(id, MIDNIGHT.plusHours(1).plusSeconds(16), CycleResult.DONE).as(StepVerifier::create)
            .verifyComplete();

        repository.lastCycle(PlugName.INDOOR).as(StepVerifier::create)
            .expectNext(new PowerCycleRun(MIDNIGHT.plusHours(1), MIDNIGHT.plusHours(1).plusSeconds(16), "Krzysztof",
                CycleResult.DONE))
            .verifyComplete();
        repository.lastCycle(PlugName.SANCTUM).as(StepVerifier::create).verifyComplete();
    }

    //a running cycle is never deleted, an old ended one is
    @Test
    void should_forget_what_is_too_old() {
        sample(PlugName.INDOOR, MIDNIGHT, 1000.0);
        sample(PlugName.INDOOR, MIDNIGHT.plusDays(2), 1000.0);
        final long ended = repository.insertCycle(PlugName.INDOOR, MIDNIGHT, null).block();
        repository.finishCycle(ended, MIDNIGHT, CycleResult.FAILED).block();
        repository.insertCycle(PlugName.INDOOR, MIDNIGHT, null).block();

        repository.deleteSamplesBefore(MIDNIGHT.plusDays(1)).as(StepVerifier::create).verifyComplete();
        repository.deleteCyclesBefore(MIDNIGHT.plusDays(1)).as(StepVerifier::create).verifyComplete();

        databaseClient.sql("SELECT count(*) AS n FROM aircon_power_sample").map(row -> row.get("n", Long.class)).one()
            .as(StepVerifier::create).expectNext(1L).verifyComplete();
        repository.runningCycles().count().as(StepVerifier::create).expectNext(1L).verifyComplete();
        databaseClient.sql("SELECT count(*) AS n FROM aircon_power_cycle").map(row -> row.get("n", Long.class)).one()
            .as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    //the words of the plug are pinned by the CHECKs of V8
    @Test
    void should_refuse_a_plug_it_does_not_know() {
        databaseClient.sql("INSERT INTO aircon_power_sample (plug, sampled_at, power) VALUES ('garage', :at, 1)")
            .bind("at", MIDNIGHT).then().as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    private void sample(final PlugName plug, final LocalDateTime at, final double totalWh) {
        sample(plug, at, totalWh, 5.0);
    }

    private void sample(final PlugName plug, final LocalDateTime at, final double totalWh, final double power) {
        repository.insertSample(new PlugReading(plug, at, power, 230.0, 0.1, totalWh, plug == PlugName.INDOOR ? true : null))
            .as(StepVerifier::create).verifyComplete();
    }
}
