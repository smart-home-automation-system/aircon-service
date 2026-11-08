package cloud.cholewa.aircon.power;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PowerWatchTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final PlugReading INDOOR = new PlugReading(PlugName.INDOOR, NOW, 612.4, 231.6, 2.87, 48210.5, true);
    private static final PlugReading SANCTUM = new PlugReading(PlugName.SANCTUM, NOW, 5.5, 244.0, 0.3, 1092063.0, null);

    private final PlugMeter meter = mock(PlugMeter.class);
    private final PlugStore store = new PlugStore();
    private final PowerRepository repository = mock(PowerRepository.class);
    private final PowerCycle cycle = mock(PowerCycle.class);
    private final PowerWatch watch = new PowerWatch(PowerCycleTest.PLUGS, meter, store, repository, cycle, CLOCK);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void setUp() {
        ((Logger) LoggerFactory.getLogger(PowerWatch.class)).addAppender(log);
        log.start();
        when(cycle.recoverInterrupted()).thenReturn(Mono.empty());
        when(repository.insertSample(any())).thenReturn(Mono.empty());
        when(repository.closeDay(any(), any())).thenReturn(Mono.empty());
        when(repository.deleteSamplesBefore(any())).thenReturn(Mono.empty());
        when(repository.deleteDaysBefore(any())).thenReturn(Mono.empty());
        when(repository.deleteCyclesBefore(any())).thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(PowerWatch.class)).detachAppender(log);
    }

    @Test
    void should_read_both_plugs_and_store_every_reading() {
        when(meter.read(eq(PlugName.INDOOR), any(), eq(NOW))).thenReturn(Mono.just(INDOOR));
        when(meter.read(eq(PlugName.SANCTUM), any(), eq(NOW))).thenReturn(Mono.just(SANCTUM));

        watch.readAll().as(StepVerifier::create).verifyComplete();

        verify(cycle).recoverInterrupted();
        verify(repository).insertSample(INDOOR);
        verify(repository).insertSample(SANCTUM);
        assertThat(store.last(PlugName.INDOOR)).contains(INDOOR);
        assertThat(store.last(PlugName.SANCTUM)).contains(SANCTUM);
    }

    //a plug that does not answer: one WARN, not one a pass, an INFO at its return; the other plug is read all the same
    @Test
    void should_warn_once_for_a_plug_that_does_not_answer() {
        when(meter.read(eq(PlugName.INDOOR), any(), any())).thenReturn(Mono.error(new IllegalStateException("timeout")));
        when(meter.read(eq(PlugName.SANCTUM), any(), any())).thenReturn(Mono.just(SANCTUM));

        watch.readAll().as(StepVerifier::create).verifyComplete();
        watch.readAll().as(StepVerifier::create).verifyComplete();
        when(meter.read(eq(PlugName.INDOOR), any(), any())).thenReturn(Mono.just(INDOOR));
        watch.readAll().as(StepVerifier::create).verifyComplete();

        assertThat(log.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.INFO);
        verify(repository, times(3)).insertSample(SANCTUM);
    }

    //a reading that cannot be stored is a WARN, it is kept in memory
    @Test
    void should_keep_a_reading_that_cannot_be_stored() {
        when(meter.read(eq(PlugName.INDOOR), any(), any())).thenReturn(Mono.just(INDOOR));
        when(meter.read(eq(PlugName.SANCTUM), any(), any())).thenReturn(Mono.just(SANCTUM));
        when(repository.insertSample(INDOOR)).thenReturn(Mono.error(new IllegalStateException("database down")));

        watch.readAll().as(StepVerifier::create).verifyComplete();

        assertThat(store.last(PlugName.INDOOR)).contains(INDOOR);
        assertThat(log.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    //every plug, the 30 days before today closed (an already closed day is left), then what is too old deleted
    @Test
    void should_close_the_days_of_the_last_month_and_forget_the_old() {
        watch.closeAndForget().as(StepVerifier::create).verifyComplete();

        final LocalDate today = NOW.toLocalDate();
        verify(repository).closeDay(PlugName.INDOOR, today.minusDays(1));
        verify(repository).closeDay(PlugName.SANCTUM, today.minusDays(30));
        verify(repository, times(60)).closeDay(any(), any());
        verify(repository).deleteSamplesBefore(NOW.minusDays(30));
        verify(repository).deleteDaysBefore(today.minusDays(730));
        verify(repository).deleteCyclesBefore(NOW.minusDays(365));
    }
}
