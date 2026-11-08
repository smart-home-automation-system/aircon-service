package cloud.cholewa.aircon.power;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

//The draw of the plugs (HAS-390): both plugs read every 30 s, one after the other - each reading in memory (PlugStore)
//and a row of aircon_power_sample; a plug that does not answer is a WARN at the first failure and an INFO at its return,
//never one a pass (the availability of the units is watched by the monitor, the plugs are not alerted on). Every hour at
//minute 15: the days of the last month not closed yet closed in aircon_power_day (from 00:15 of the next day), and what
//is older than it is kept for deleted
@Slf4j
@Component
@RequiredArgsConstructor
public class PowerWatch {

    //below the delay of 30 s: two plugs, each bounded by the call timeout of shelly-client
    static final Duration LIMIT = Duration.ofSeconds(25);
    static final Duration DAYS_LIMIT = Duration.ofMinutes(5);
    static final Duration SAMPLES_KEPT = Duration.ofDays(30);
    static final int DAYS_KEPT = 730;
    static final Duration CYCLES_KEPT = Duration.ofDays(365);
    //the days closed again after a gap: the samples go after 30 days, so an older day can no longer be counted
    static final int DAYS_CLOSED_BACK = 30;

    private final PlugProperties properties;
    private final PlugMeter plugMeter;
    private final PlugStore plugStore;
    private final PowerRepository repository;
    private final PowerCycle powerCycle;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT30S")
    Mono<Void> watch() {
        return ScheduledPass.bounded("air conditioner plugs", LIMIT, this::readAll);
    }

    @Scheduled(cron = "0 15 * * * *")
    Mono<Void> closeDays() {
        return ScheduledPass.bounded("air conditioner power days", DAYS_LIMIT, this::closeAndForget);
    }

    Mono<Void> readAll() {
        return powerCycle.recoverInterrupted()
            .thenMany(Flux.fromArray(PlugName.values()))
            .concatMap(this::read)
            .then();
    }

    private Mono<Void> read(final PlugName name) {
        return Mono.defer(() -> {
            final LocalDateTime at = LocalDateTime.now(clock);
            return plugMeter.read(name, properties.of(name), at)
                .flatMap(reading -> Mono.fromRunnable(() -> answered(reading))
                    .then(repository.insertSample(reading)
                        .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                            "The reading of the plug {} could not be stored: {}", name.getValue(), failure.toString())))))
                .onErrorResume(failure -> Mono.fromRunnable(() -> failed(name, at, failure)));
        });
    }

    private void answered(final PlugReading reading) {
        if (plugStore.answered(reading)) {
            log.info("The plug {} answers again: {} W", reading.plug().getValue(), reading.power());
        }
    }

    private void failed(final PlugName name, final LocalDateTime at, final Throwable failure) {
        if (plugStore.failed(name, at)) {
            log.warn("The plug {} could not be read, tried again at every pass: {}", name.getValue(),
                failure.getMessage());
        }
    }

    Mono<Void> closeAndForget() {
        return Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            final LocalDate today = now.toLocalDate();
            return Flux.fromIterable(Arrays.asList(PlugName.values()))
                .concatMap(plug -> Flux.range(1, DAYS_CLOSED_BACK)
                    .map(today::minusDays)
                    .concatMap(day -> repository.closeDay(plug, day)))
                .then(repository.deleteSamplesBefore(now.minus(SAMPLES_KEPT)))
                .then(repository.deleteDaysBefore(today.minusDays(DAYS_KEPT)))
                .then(repository.deleteCyclesBefore(now.minus(CYCLES_KEPT)))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The days of the plugs could not be closed, tried again in an hour: {}", failure.toString())));
        });
    }
}
