package cloud.cholewa.aircon.window;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

//The stops by an open window (HAS-384, V5) on the tables the migrations create, in a PostgreSQL of Testcontainers -
//DISTINCT ON and the partial unique index are PostgreSQL's; the setup of AirconStateRepositoryTest
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(WindowHoldRepository.class)
class WindowHoldRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 7, 18, 30, 0, 123456000);
    private static final UnitStatus COOLING = new UnitStatus(true, Mode.COOL, 24, 26, FanSpeed.MEDIUM_LOW, 3, 1, false,
        true, false, true, "hid");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WindowHoldRepository sut;

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
    void forgetTheStops() {
        databaseClient.sql("DELETE FROM aircon_window_hold").then().as(StepVerifier::create).verifyComplete();
    }

    //what the unit was doing is read back switched on, the room temperature, the display and the firmware left out
    @Test
    void should_store_a_stop_and_read_it_back() {
        sut.insert(140, AT.minusMinutes(4), AT, COOLING).as(StepVerifier::create).verifyComplete();

        sut.latest().as(StepVerifier::create)
            .assertNext(hold -> {
                assertThat(hold.id()).isNotNull();
                assertThat(hold.unitId()).isEqualTo(140);
                assertThat(hold.windowOpenSince()).isEqualTo(AT.minusMinutes(4));
                assertThat(hold.heldSince()).isEqualTo(AT);
                assertThat(hold.before()).isEqualTo(new UnitStatus(true, Mode.COOL, 24, null, FanSpeed.MEDIUM_LOW, 3, 1,
                    false, true, false, null, null));
                assertThat(hold.open()).isTrue();
                assertThat(hold.outcome()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_store_what_a_unit_did_not_report_as_null() {
        sut.insert(140, null, AT, new UnitStatus(true, null, null, null, null, null, null, null, null, null, null, null))
            .as(StepVerifier::create).verifyComplete();

        sut.latest().as(StepVerifier::create)
            .assertNext(hold -> assertThat(hold.before()).isEqualTo(new UnitStatus(true, null, null, null, null, null,
                null, null, null, null, null, null)))
            .verifyComplete();
    }

    @Test
    void should_close_a_stop_once() {
        sut.insert(140, AT.minusMinutes(4), AT, COOLING).as(StepVerifier::create).verifyComplete();
        final long id = sut.latest().blockFirst().id();

        sut.close(id, HoldOutcome.RESTORED, AT.plusMinutes(10)).as(StepVerifier::create).verifyComplete();
        sut.close(id, HoldOutcome.EXPIRED, AT.plusMinutes(20)).as(StepVerifier::create).verifyComplete();

        sut.latest().as(StepVerifier::create)
            .assertNext(hold -> {
                assertThat(hold.open()).isFalse();
                assertThat(hold.closedAt()).isEqualTo(AT.plusMinutes(10));
                assertThat(hold.outcome()).isEqualTo(HoldOutcome.RESTORED);
            })
            .verifyComplete();
    }

    //the latest stop of every unit, an older one of the same unit left out
    @Test
    void should_read_the_latest_stop_of_every_unit() {
        sut.insert(140, AT.minusHours(3), AT.minusHours(3), COOLING).as(StepVerifier::create).verifyComplete();
        final long older = sut.latest().blockFirst().id();
        sut.close(older, HoldOutcome.EXPIRED, AT.minusHours(1)).as(StepVerifier::create).verifyComplete();
        sut.insert(140, AT.minusMinutes(4), AT, COOLING).as(StepVerifier::create).verifyComplete();
        sut.insert(141, AT.minusMinutes(5), AT, COOLING).as(StepVerifier::create).verifyComplete();

        sut.latest().collectList().as(StepVerifier::create)
            .assertNext(latest -> assertThat(latest)
                .extracting(WindowHold::unitId, WindowHold::heldSince, WindowHold::open)
                .containsExactly(
                    tuple(140L, AT, true),
                    tuple(141L, AT, true)))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_second_open_stop_of_a_unit() {
        sut.insert(140, AT.minusMinutes(4), AT, COOLING).as(StepVerifier::create).verifyComplete();

        sut.insert(140, AT.minusMinutes(4), AT.plusMinutes(1), COOLING).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    //the stops that ended before a time; an open one and one that ended later are kept
    @Test
    void should_delete_the_stops_that_ended_before_a_time() {
        sut.insert(140, AT.minusDays(100), AT.minusDays(100), COOLING).as(StepVerifier::create).verifyComplete();
        sut.close(sut.latest().blockFirst().id(), HoldOutcome.RESTORED, AT.minusDays(95)).as(StepVerifier::create)
            .verifyComplete();
        sut.insert(141, AT.minusDays(10), AT.minusDays(10), COOLING).as(StepVerifier::create).verifyComplete();
        sut.close(sut.latest().filter(hold -> hold.unitId() == 141).blockFirst().id(), HoldOutcome.EXPIRED,
            AT.minusDays(9)).as(StepVerifier::create).verifyComplete();
        sut.insert(142, AT.minusDays(100), AT.minusDays(100), COOLING).as(StepVerifier::create).verifyComplete();

        sut.deleteClosedBefore(AT.minusDays(90)).as(StepVerifier::create).verifyComplete();

        sut.latest().map(WindowHold::unitId).collectList().as(StepVerifier::create)
            .assertNext(units -> assertThat(units).containsExactly(141L, 142L))
            .verifyComplete();
    }

    //the words of the outcome are pinned by the CHECK of V5
    @Test
    void should_store_every_outcome() {
        for (final HoldOutcome outcome : HoldOutcome.values()) {
            sut.insert(140, AT, AT, COOLING).as(StepVerifier::create).verifyComplete();
            final long id = sut.latest().blockFirst().id();
            sut.close(id, outcome, AT.plusMinutes(1)).as(StepVerifier::create).verifyComplete();
            sut.latest().as(StepVerifier::create)
                .assertNext(hold -> assertThat(hold.outcome()).isEqualTo(outcome))
                .verifyComplete();
        }
    }
}
