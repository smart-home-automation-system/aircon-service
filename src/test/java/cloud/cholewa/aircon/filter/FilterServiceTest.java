package cloud.cholewa.aircon.filter;

import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FilterServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE, "192.0.2.59",
        "02005e0000a1");

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final FilterRepository repository = mock(FilterRepository.class);
    private final FilterStore store = new FilterStore();
    private final FilterService service = new FilterService(registry, repository, store, CLOCK);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(repository.cleaned(anyLong(), any())).thenReturn(Mono.empty());
    }

    //a cleaning stored and the hours at 0 at once in the reads
    @Test
    void should_record_a_cleaning() {
        store.put(140, new FilterState(203.4, null, true));

        service.cleaned(140).as(StepVerifier::create).verifyComplete();

        verify(repository).cleaned(140, NOW);
        assertThat(store.of(140)).contains(new FilterState(0, NOW, false));
    }

    @Test
    void should_refuse_a_unit_not_registered() {
        service.cleaned(999).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.NOT_FOUND_AIRCON)))
            .verify();
        verify(repository, never()).cleaned(anyLong(), any());
    }

    @Test
    void should_answer_the_runs_of_a_unit() {
        final List<UnitRun> runs = List.of(new UnitRun(NOW.minusHours(2), true, Mode.HEAT, null, null, ChangeSource.RULE));
        when(repository.runs(140, NOW.minusDays(1), NOW)).thenReturn(Flux.fromIterable(runs));

        service.runs(140, NOW.minusDays(1), NOW).as(StepVerifier::create).expectNext(runs).verifyComplete();
    }

    @Test
    void should_refuse_a_range_longer_than_a_month() {
        service.runs(140, NOW.minusDays(32), NOW).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.INVALID_RANGE)))
            .verify();
    }
}
