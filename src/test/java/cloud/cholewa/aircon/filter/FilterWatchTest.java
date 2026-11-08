package cloud.cholewa.aircon.filter;

import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.commons.notification.NotificationPublisher;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FilterWatchTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final LocalDateTime CLEANED = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE, "192.0.2.59",
        "02005e0000a1");
    private static final AirConditioner SANCTUM = new AirConditioner(144, "Air conditioner", RoomName.SANCTUM,
        "192.0.2.60", "02005e0000a5");

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final FilterRepository repository = mock(FilterRepository.class);
    private final FilterStore store = new FilterStore();
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final FilterWatch watch = new FilterWatch(registry, repository, store, new FilterProperties(200, 50), publisher,
        CLOCK);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, SANCTUM)));
        when(repository.filters()).thenReturn(Flux.just(new FilterRecord(140, CLEANED, null)));
        when(repository.reminded(anyLong(), anyDouble())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    //the office counted since its cleaning and due - one info and the step stored; sanctum since its history began
    @Test
    void should_count_every_unit_and_name_a_filter_due() {
        when(repository.hours(140, CLEANED, NOW)).thenReturn(Mono.just(203.4));
        when(repository.hours(144, null, NOW)).thenReturn(Mono.just(12.0));

        watch.countAll().as(StepVerifier::create).verifyComplete();

        assertThat(store.of(140)).contains(new FilterState(203.4, CLEANED, true));
        assertThat(store.of(144)).contains(new FilterState(12.0, null, false));
        verify(publisher).publishInfo(
            "Clean the filter: Air conditioner in office (140) has run 203 h since its cleaning on 2026-10-01.");
        verify(repository).reminded(140, 200.0);
    }

    @Test
    void should_not_name_a_step_named_already() {
        when(repository.filters()).thenReturn(Flux.just(new FilterRecord(140, CLEANED, 200.0)));
        when(repository.hours(140, CLEANED, NOW)).thenReturn(Mono.just(230.0));
        when(repository.hours(144, null, NOW)).thenReturn(Mono.just(12.0));

        watch.countAll().as(StepVerifier::create).verifyComplete();

        verify(publisher, never()).publishInfo(anyString());
    }

    //the broker did not take it: the step is not stored, so the next pass names it again
    @Test
    void should_keep_a_step_the_broker_did_not_take() {
        when(repository.hours(140, CLEANED, NOW)).thenReturn(Mono.just(203.4));
        when(repository.hours(144, null, NOW)).thenReturn(Mono.just(12.0));
        when(publisher.publishInfo(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));

        watch.countAll().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).reminded(anyLong(), anyDouble());
    }

    //one unit that fails cuts nothing else
    @Test
    void should_count_the_others_when_one_fails() {
        when(repository.hours(140, CLEANED, NOW)).thenReturn(Mono.error(new IllegalStateException("database down")));
        when(repository.hours(144, null, NOW)).thenReturn(Mono.just(12.0));

        watch.countAll().as(StepVerifier::create).verifyComplete();

        assertThat(store.of(140)).isEmpty();
        assertThat(store.of(144)).isPresent();
    }
}
