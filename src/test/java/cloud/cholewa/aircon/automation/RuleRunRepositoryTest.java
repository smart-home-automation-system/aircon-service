package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.automation.AutomationEvaluator.Episode;
import cloud.cholewa.aircon.automation.EpisodeRepository.Acted;
import cloud.cholewa.aircon.state.ChangeSource;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//The automatic commands and the episodes of the automation (HAS-385, V6) on the tables the migrations create, in a
//PostgreSQL of Testcontainers - ON CONFLICT is PostgreSQL's; the setup of AirconStateRepositoryTest
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({RuleRunRepository.class, EpisodeRepository.class})
class RuleRunRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 7, 19, 0, 0, 123456000);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private RuleRunRepository runs;

    @Autowired
    private EpisodeRepository episodes;

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
        databaseClient.sql("DELETE FROM aircon_rule_run").then().as(StepVerifier::create).verifyComplete();
        databaseClient.sql("DELETE FROM aircon_automation_episode").then().as(StepVerifier::create).verifyComplete();
    }

    //the words of the source and the result are pinned by the CHECKs of V6
    @Test
    void should_store_every_result_of_every_automatic_source() {
        for (final ChangeSource source : List.of(ChangeSource.RULE, ChangeSource.WINDOW, ChangeSource.EMPTY_HOUSE,
            ChangeSource.GRID_OUTAGE)) {
            for (final RunResult result : RunResult.values()) {
                insert(AT, source, AT, result);
            }
        }
        insert(AT, ChangeSource.WINDOW, null, RunResult.SENT);

        databaseClient.sql("SELECT count(*) AS n FROM aircon_rule_run").map(row -> row.get("n", Long.class)).one()
            .as(StepVerifier::create).expectNext(13L).verifyComplete();
    }

    @Test
    void should_refuse_a_source_that_is_not_automatic() {
        runs.insert(new RuleRun(AT, ChangeSource.DASHBOARD, 140, null, "{Pow=0}", RunResult.SENT))
            .as(StepVerifier::create).expectError(DataIntegrityViolationException.class).verify();
    }

    @Test
    void should_delete_the_runs_before_a_time() {
        insert(AT.minusDays(91), ChangeSource.GRID_OUTAGE, AT.minusDays(91), RunResult.SENT);
        insert(AT.minusDays(89), ChangeSource.EMPTY_HOUSE, AT.minusDays(89), RunResult.SENT);

        runs.deleteBefore(AT.minusDays(90)).as(StepVerifier::create).verifyComplete();

        databaseClient.sql("SELECT source FROM aircon_rule_run").map(row -> row.get("source", String.class)).all()
            .collectList().as(StepVerifier::create).expectNext(List.of("empty-house")).verifyComplete();
    }

    //one row a source, the latest episode replacing the one before; a key not known as null
    @Test
    void should_keep_the_last_episode_of_each_source() {
        episodes.save(ChangeSource.EMPTY_HOUSE, new Episode(AT.minusDays(1), AT.minusDays(1))).as(StepVerifier::create)
            .verifyComplete();
        episodes.save(ChangeSource.EMPTY_HOUSE, new Episode(AT.minusMinutes(15), AT)).as(StepVerifier::create)
            .verifyComplete();
        episodes.save(ChangeSource.GRID_OUTAGE, new Episode(null, AT)).as(StepVerifier::create).verifyComplete();

        episodes.all().collectList().as(StepVerifier::create)
            .assertNext(all -> assertThat(all).containsExactlyInAnyOrder(
                new Acted(ChangeSource.EMPTY_HOUSE, new Episode(AT.minusMinutes(15), AT)),
                new Acted(ChangeSource.GRID_OUTAGE, new Episode(null, AT))))
            .verifyComplete();
    }

    @Test
    void should_refuse_an_episode_of_a_source_that_has_none() {
        episodes.save(ChangeSource.WINDOW, new Episode(AT, AT)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
    }

    private void insert(final LocalDateTime at, final ChangeSource source, final LocalDateTime episode,
                        final RunResult result) {
        runs.insert(new RuleRun(at, source, 140, episode, "{Pow=0}", result)).as(StepVerifier::create)
            .verifyComplete();
    }
}
