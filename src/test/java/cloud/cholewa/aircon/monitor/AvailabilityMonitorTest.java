package cloud.cholewa.aircon.monitor;

import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvailabilityMonitorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T14:30:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");
    private static final AirConditioner LIVIA = new AirConditioner(141, "Air conditioner", RoomName.LIVIA,
        "192.0.2.56", "02005e0000a2");

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final StateStore store = mock(StateStore.class);
    private final AvailabilityAlertRepository repository = mock(AvailabilityAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final AvailabilityMonitor monitor = new AvailabilityMonitor(registry, store, repository, publisher,
        new MonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1)), CLOCK);

    @BeforeEach
    void setUp() {
        when(repository.findAll()).thenReturn(Flux.empty());
        when(repository.insert(any())).thenReturn(Mono.empty());
        when(repository.delete(anyLong())).thenReturn(Mono.empty());
        when(repository.reminded(anyLong(), any())).thenReturn(Mono.empty());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    //one alert a pass naming every unit out; the rows written once the broker took it
    @Test
    void should_alert_every_unit_out_in_one_message() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, LIVIA)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(null, false, null, NOW.minusMinutes(6))));
        when(store.of(141)).thenReturn(Optional.of(new UnitState(null, false, null, NOW.minusMinutes(7))));

        monitor.checkOnce().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishAlert("Not answering: Air conditioner in office (140) since 2026-11-07 15:24 (6 min);"
            + " Air conditioner in livia (141) since 2026-11-07 15:23 (7 min).");
        verify(repository).insert(new Alert(140, "Air conditioner", "office", NOW.minusMinutes(6), NOW));
        verify(repository).insert(new Alert(141, "Air conditioner", "livia", NOW.minusMinutes(7), NOW));
    }

    @Test
    void should_tell_the_return_and_close_the_alert() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(null, true, NOW, null)));
        when(repository.findAll()).thenReturn(Flux.just(new Alert(140, "Air conditioner", "office",
            NOW.minusMinutes(20), NOW.minusMinutes(14))));

        monitor.checkOnce().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishInfo("Answering again: Air conditioner in office (140) - out from 2026-11-07 15:10 to"
            + " 2026-11-07 15:30 (20 min).");
        verify(repository).delete(140);
    }

    //a notification that fails changes nothing in the table - it is due again at the next pass
    @Test
    void should_keep_the_table_when_the_publish_fails() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(null, false, null, NOW.minusMinutes(6))));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));

        monitor.checkOnce().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).insert(any());
    }

    //before the registry was read nothing is judged - every alert would look forgotten
    @Test
    void should_judge_nothing_before_the_registry_is_known() {
        when(registry.current()).thenReturn(Optional.empty());

        monitor.checkOnce().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).findAll();
    }

    @Test
    void should_remind_an_hour_after_the_alert() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(null, false, null, NOW.minusMinutes(66))));
        when(repository.findAll()).thenReturn(Flux.just(new Alert(140, "Air conditioner", "office",
            NOW.minusMinutes(66), NOW.minusMinutes(60))));

        monitor.checkOnce().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishReminder("Still not answering: Air conditioner in office (140) since 2026-11-07 14:24"
            + " (1 h 6 min).");
        verify(repository).reminded(140, NOW);
    }
}
