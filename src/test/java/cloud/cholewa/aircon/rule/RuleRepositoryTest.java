package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

//The rules of the units (HAS-386, V7) on the table the migrations create, in a PostgreSQL of Testcontainers - ON
//CONFLICT and the checks are PostgreSQL's; the setup of AirconStateRepositoryTest
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(RuleRepository.class)
class RuleRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 7, 20, 0, 0, 123456000);
    private static final AirconRule SANCTUM = rule(144, "25.0", "1.0", 22, true);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private RuleRepository sut;

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
    void forgetTheRules() {
        databaseClient.sql("DELETE FROM aircon_rule").then().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_a_rule_and_read_it_back() {
        sut.save(SANCTUM).as(StepVerifier::create).verifyComplete();

        sut.find(144).as(StepVerifier::create).expectNext(SANCTUM).verifyComplete();
        sut.find(140).as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_replace_a_rule_whole() {
        sut.save(SANCTUM).as(StepVerifier::create).verifyComplete();
        final AirconRule changed = new AirconRule(144, new AirconRule.KeepBelow(false, new BigDecimal("26.5"),
            new BigDecimal("2.0"), 23), new AirconRule.HeatBySchedule(false), AT.plusHours(1));

        sut.save(changed).as(StepVerifier::create).verifyComplete();

        sut.findAll().as(StepVerifier::create).expectNext(changed).verifyComplete();
    }

    @Test
    void should_read_every_rule_by_unit() {
        sut.save(SANCTUM).as(StepVerifier::create).verifyComplete();
        sut.save(rule(140, "24.0", "1.0", 22, false)).as(StepVerifier::create).verifyComplete();

        sut.findAll().map(AirconRule::unitId).as(StepVerifier::create).expectNext(140L, 144L).verifyComplete();
    }

    @Test
    void should_delete_a_rule_and_say_whether_there_was_one() {
        sut.save(SANCTUM).as(StepVerifier::create).verifyComplete();

        sut.delete(144).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.delete(144).as(StepVerifier::create).expectNext(false).verifyComplete();
    }

    //the checks of V7 hold what RuleRules allows, a second line behind it
    @Test
    void should_refuse_what_the_rules_refuse() {
        sut.save(rule(144, "30.5", "1.0", 22, true)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        sut.save(rule(144, "25.2", "1.0", 22, true)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        sut.save(rule(144, "25.0", "0.3", 22, true)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        sut.save(rule(144, "25.0", "1.0", 21, true)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        sut.save(rule(144, "23.0", "1.0", 24, true)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
    }

    private static AirconRule rule(final long unitId, final String threshold, final String hysteresis, final int target,
                                   final boolean enabled) {
        return new AirconRule(unitId, new AirconRule.KeepBelow(enabled, new BigDecimal(threshold),
            new BigDecimal(hysteresis), target), new AirconRule.HeatBySchedule(false), AT);
    }
}
