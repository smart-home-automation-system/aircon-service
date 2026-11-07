package cloud.cholewa.aircon.state;

import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//The SQL of the state (HAS-381, V2) is PostgreSQL's own (ON CONFLICT, IS DISTINCT FROM), so it runs against a PostgreSQL
//in Docker on the tables the migrations of the service create - the setup of the repository tests of water-service.
//Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(AirconStateRepository.class)
class AirconStateRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 7, 13, 15, 45, 123456000);
    private static final UnitStatus COOLING = new UnitStatus(true, Mode.COOL, 22, 24, FanSpeed.MEDIUM_LOW, 0, 2, false,
        false, false, true, "hid");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private AirconStateRepository sut;

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
    void forgetTheUnits() {
        databaseClient.sql("DELETE FROM aircon_state").then().as(StepVerifier::create).verifyComplete();
        databaseClient.sql("DELETE FROM aircon_state_change").then().as(StepVerifier::create).verifyComplete();
    }

    //the current row: every value stored, read back as the unit reported it (the firmware is not stored)
    @Test
    void should_store_the_state_of_a_unit_and_read_it_back() {
        sut.recordState(140, COOLING, AT).as(StepVerifier::create).verifyComplete();

        sut.currentStates().as(StepVerifier::create)
            .expectNext(new StoredState(140, new UnitStatus(true, Mode.COOL, 22, 24, FanSpeed.MEDIUM_LOW, 0, 2, false,
                false, false, true, null), AT))
            .verifyComplete();
    }

    //a value not reported is NULL, and read back as not known
    @Test
    void should_keep_what_a_unit_did_not_report_unknown() {
        final UnitStatus partial = new UnitStatus(false, null, null, null, null, null, null, null, null, null, null, null);

        sut.recordState(141, partial, AT).as(StepVerifier::create).verifyComplete();

        sut.currentStates().as(StepVerifier::create)
            .expectNext(new StoredState(141, partial, AT))
            .verifyComplete();
    }

    //one row a unit: the same values move last_checked_at only, a changed value changed_at too
    @Test
    void should_move_changed_at_only_when_a_value_changed() {
        sut.recordState(140, COOLING, AT).as(StepVerifier::create).verifyComplete();
        sut.recordState(140, COOLING, AT.plusSeconds(30)).as(StepVerifier::create).verifyComplete();
        assertThat(times()).isEqualTo(Map.of("changed_at", AT, "last_checked_at", AT.plusSeconds(30), "rows", 1L));

        final UnitStatus warmer = new UnitStatus(true, Mode.COOL, 22, 25, FanSpeed.MEDIUM_LOW, 0, 2, false, false, false,
            true, "hid");
        sut.recordState(140, warmer, AT.plusSeconds(60)).as(StepVerifier::create).verifyComplete();
        assertThat(times()).isEqualTo(Map.of("changed_at", AT.plusSeconds(60), "last_checked_at", AT.plusSeconds(60),
            "rows", 1L));
    }

    @Test
    void should_record_a_change_with_who_made_it() {
        sut.recordChange(140, COOLING, ChangeSource.FIRST_READ, AT).as(StepVerifier::create).verifyComplete();
        sut.recordChange(140, new UnitStatus(false, Mode.COOL, 22, 24, FanSpeed.AUTO, 0, 2, false, false, false, true,
            null), ChangeSource.REMOTE, AT.plusMinutes(5)).as(StepVerifier::create).verifyComplete();

        databaseClient.sql("SELECT unit_id, changed_at, power, mode, target_temperature, fan_speed, source"
                + " FROM aircon_state_change ORDER BY id")
            .fetch().all()
            .as(StepVerifier::create)
            .expectNext(Map.of("unit_id", 140L, "changed_at", AT, "power", true, "mode", "cool", "target_temperature", 22,
                "fan_speed", "medium-low", "source", "first-read"))
            .expectNext(Map.of("unit_id", 140L, "changed_at", AT.plusMinutes(5), "power", false, "mode", "cool",
                "target_temperature", 22, "fan_speed", "auto", "source", "remote"))
            .verifyComplete();
    }

    //the words of ChangeSource are the words the CHECK of the table takes
    @Test
    void should_read_the_last_change_of_every_unit() {
        sut.recordChange(140, COOLING, ChangeSource.FIRST_READ, AT.minusHours(2)).as(StepVerifier::create).verifyComplete();
        sut.recordChange(140, COOLING, ChangeSource.RULE, AT.minusHours(1)).as(StepVerifier::create).verifyComplete();
        sut.recordChange(141, COOLING, ChangeSource.REMOTE, AT).as(StepVerifier::create).verifyComplete();

        sut.lastChanges().collectList().as(StepVerifier::create)
            .assertNext(changes -> assertThat(changes).containsExactlyInAnyOrder(
                new AirconStateRepository.LastChange(140, ChangeSource.RULE, AT.minusHours(1)),
                new AirconStateRepository.LastChange(141, ChangeSource.REMOTE, AT)))
            .verifyComplete();
    }

    @Test
    void should_take_every_source_of_a_change() {
        for (ChangeSource source : ChangeSource.values()) {
            sut.recordChange(142, COOLING, source, AT).as(StepVerifier::create).verifyComplete();
        }

        databaseClient.sql("SELECT count(*) AS changes FROM aircon_state_change").fetch().one()
            .as(StepVerifier::create)
            .expectNext(Map.of("changes", (long) ChangeSource.values().length))
            .verifyComplete();
    }

    private Map<String, Object> times() {
        return databaseClient.sql("SELECT min(changed_at) AS changed_at, max(last_checked_at) AS last_checked_at,"
                + " count(*) AS rows FROM aircon_state")
            .fetch().one().block();
    }
}
