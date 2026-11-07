package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.api.model.RuleBody;
import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T19:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final RuleBody SANCTUM = new RuleBody(new RuleBody.KeepBelow(true, new BigDecimal("25"), null, 22),
        null);

    private final RuleRepository repository = mock(RuleRepository.class);
    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final RuleService service = new RuleService(repository, registry, CLOCK);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(new AirConditioner(144, "Air conditioner",
            RoomName.SANCTUM, "192.0.2.60", "02005e0000a5"))));
        when(repository.save(any())).thenReturn(Mono.empty());
    }

    @Test
    void should_store_a_rule_and_answer_it() {
        final AirconRule stored = new AirconRule(144, new AirconRule.KeepBelow(true, new BigDecimal("25.0"),
            new BigDecimal("1.0"), 22), new AirconRule.HeatBySchedule(false), NOW);

        service.save(144, SANCTUM).as(StepVerifier::create).expectNext(stored).verifyComplete();

        verify(repository).save(stored);
    }

    @Test
    void should_refuse_a_rule_the_rules_refuse() {
        service.save(144, new RuleBody(new RuleBody.KeepBelow(true, new BigDecimal("35"), null, 22), null))
            .as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> {
                    assertThat(refused.getError()).isEqualTo(AirconError.INVALID_RULE);
                    assertThat(refused.getMessage()).isEqualTo("The threshold is 20 to 30 C by 0.5, not 35");
                }))
            .verify();
        verify(repository, never()).save(any());
    }

    @Test
    void should_refuse_a_rule_of_a_unit_not_registered() {
        service.save(999, SANCTUM).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.NOT_FOUND_AIRCON)))
            .verify();
        when(registry.current()).thenReturn(Optional.empty());
        service.save(144, SANCTUM).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.REGISTRY_UNAVAILABLE)))
            .verify();
    }

    @Test
    void should_answer_not_found_for_a_unit_without_a_rule() {
        when(repository.find(140)).thenReturn(Mono.empty());
        when(repository.delete(140)).thenReturn(Mono.just(false));

        service.rule(140).as(StepVerifier::create).expectErrorSatisfies(failure -> assertThat(failure)
            .isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.NOT_FOUND_RULE))).verify();
        service.delete(140).as(StepVerifier::create).expectError(AirconException.class).verify();
    }

    @Test
    void should_delete_a_rule() {
        when(repository.delete(144)).thenReturn(Mono.just(true));

        service.delete(144).as(StepVerifier::create).verifyComplete();
    }
}
