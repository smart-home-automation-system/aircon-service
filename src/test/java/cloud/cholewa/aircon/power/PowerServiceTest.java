package cloud.cholewa.aircon.power;

import cloud.cholewa.aircon.api.model.PlugReply;
import cloud.cholewa.aircon.api.model.PowerCycleReply;
import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PowerServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final LocalDate TODAY = NOW.toLocalDate();

    private final PlugStore store = new PlugStore();
    private final PowerRepository repository = mock(PowerRepository.class);
    private final PowerCycle cycle = mock(PowerCycle.class);
    private final PowerService service = new PowerService(PowerCycleTest.PLUGS, store, repository, cycle, CLOCK);

    @BeforeEach
    void setUp() {
        when(repository.energy(any(), any(), any())).thenReturn(Mono.just(Optional.empty()));
        when(repository.days(any(), any(), any())).thenReturn(Mono.just(Optional.empty()));
    }

    //the indoor plug with its relay, the draw and the energy; the meter of sanctum not read yet - its name, rooms and no
    //relay, nothing else
    @Test
    void should_answer_both_plugs_with_what_is_known() {
        store.answered(new PlugReading(PlugName.INDOOR, NOW.minusSeconds(10), 612.4, 231.6, 2.87, 48210.5, true));
        when(repository.energy(PlugName.INDOOR, TODAY.atStartOfDay(), NOW)).thenReturn(Mono.just(Optional.of(3.2)));
        when(repository.days(PlugName.INDOOR, TODAY.minusDays(7), TODAY)).thenReturn(Mono.just(Optional.of(21.5)));
        when(repository.days(PlugName.INDOOR, TODAY.minusDays(30), TODAY)).thenReturn(Mono.just(Optional.of(80.0)));

        service.plugs().as(StepVerifier::create)
            .expectNext(List.of(
                new PlugReply("indoor", List.of("office", "livia", "tobi", "bedroom"), true, 612.4, 231.6, 2.87, true, true,
                    true, NOW.minusSeconds(10), 3.2, 21.5, 80.0),
                new PlugReply("sanctum", List.of("sanctum"), false, null, null, null, null, null, null, null, null, null,
                    null)))
            .verifyComplete();
    }

    //a reading older than 2 min is not the plug now; a plug that stopped answering is unreachable
    @Test
    void should_leave_out_a_draw_that_is_not_the_plug_now() {
        store.answered(new PlugReading(PlugName.SANCTUM, NOW.minusMinutes(5), 5.5, 244.0, 0.3, 1092063.0, null));
        store.failed(PlugName.SANCTUM, NOW.minusMinutes(4));

        service.plugs().as(StepVerifier::create)
            .assertNext(plugs -> assertThat(plugs.get(1)).isEqualTo(new PlugReply("sanctum", List.of("sanctum"), false,
                null, null, null, null, null, false, NOW.minusMinutes(5), null, null, null)))
            .verifyComplete();
    }

    //a database that fails leaves the energy out, the draw is answered
    @Test
    void should_answer_the_draw_when_the_energy_cannot_be_read() {
        store.answered(new PlugReading(PlugName.INDOOR, NOW.minusSeconds(10), 3.0, 231.6, 0.1, 48210.5, true));
        when(repository.energy(eq(PlugName.INDOOR), any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));

        service.plugs().as(StepVerifier::create)
            .assertNext(plugs -> {
                assertThat(plugs.getFirst().power()).isEqualTo(3.0);
                assertThat(plugs.getFirst().compressorRunning()).isFalse();
                assertThat(plugs.getFirst().todayKwh()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_tell_whether_the_plug_of_a_room_feeds_a_running_compressor() {
        store.answered(new PlugReading(PlugName.INDOOR, NOW.minusSeconds(10), 600.0, 231.6, 2.6, 48210.5, true));
        store.answered(new PlugReading(PlugName.SANCTUM, NOW.minusSeconds(10), 5.5, 244.0, 0.3, 1092063.0, null));

        assertThat(service.compressorRunning("tobi")).isTrue();
        assertThat(service.compressorRunning("sanctum")).isFalse();
        assertThat(service.compressorRunning("kitchen")).as("no plug").isNull();
        assertThat(service.compressorRunning(null)).isNull();
    }

    //5-minute buckets for a day; the plug and the range checked first
    @Test
    void should_answer_the_samples_in_buckets() {
        final List<PowerBucket> buckets = List.of(new PowerBucket(NOW, 600.0, 580.0, 620.0));
        when(repository.buckets(PlugName.INDOOR, NOW.minusDays(1), NOW, 300)).thenReturn(Flux.fromIterable(buckets));

        service.samples("indoor", NOW.minusDays(1), NOW).as(StepVerifier::create).expectNext(buckets).verifyComplete();
        verify(repository).buckets(PlugName.INDOOR, NOW.minusDays(1), NOW, 300);
    }

    @Test
    void should_refuse_an_unknown_plug_or_a_wrong_range() {
        service.samples("garage", NOW.minusDays(1), NOW).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.NOT_FOUND_PLUG)))
            .verify();
        service.samples("indoor", NOW, NOW.minusDays(1)).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.INVALID_RANGE)))
            .verify();
        service.samples("indoor", NOW.minusDays(32), NOW).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).hasMessage("The range must not be longer than 31 days"))
            .verify();
    }

    @Test
    void should_answer_whether_a_cycle_is_allowed_and_the_last_one() {
        final PowerCycleRun last = new PowerCycleRun(NOW.minusHours(1), NOW.minusHours(1).plusSeconds(16), "Krzysztof",
            CycleResult.DONE);
        when(cycle.lastRun()).thenReturn(Mono.just(last));
        when(cycle.refusal()).thenReturn(Optional.of("Air conditioner 140 is on"));

        service.powerCycle().as(StepVerifier::create)
            .expectNext(new PowerCycleReply(false, "Air conditioner 140 is on", last))
            .verifyComplete();

        when(cycle.lastRun()).thenReturn(Mono.empty());
        when(cycle.refusal()).thenReturn(Optional.empty());
        service.powerCycle().as(StepVerifier::create).expectNext(new PowerCycleReply(true, null, null)).verifyComplete();
    }
}
