package cloud.cholewa.aircon.monitor;

import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Watched;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.home.model.RoomName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

//The availability monitor (HAS-383): every minute the units of the registry are judged by AvailabilityEvaluator - by
//whole passes of the tracker, a unit out once it has not answered for offline-after - against the alerts stored in
//aircon_availability_alert, and what is due goes to the technical channel (alert / info, no SMS) through
//NotificationPublisher: one return, one alert and one reminder a pass at most, each naming every unit, so that a Wi-Fi
//outage is one message and not five. The table changes only once the broker took the message; a notification that fails
//is tried again with the next pass, and neither fails nor stalls the pass for longer than PUBLISH_TIMEOUT
@Slf4j
@Component
@RequiredArgsConstructor
public class AvailabilityMonitor {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    //below the delay of a minute: three publishes of 30 s at most would cut a pass, and the next one judges again
    static final Duration LIMIT = Duration.ofSeconds(55);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UnitRegistry unitRegistry;
    private final StateStore stateStore;
    private final AvailabilityAlertRepository alertRepository;
    private final NotificationPublisher notificationPublisher;
    private final MonitorProperties properties;
    private final Clock clock;

    //two minutes in: the registry is read and every unit was read by the tracker at least once
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("air conditioner availability monitor", LIMIT, this::checkOnce);
    }

    Mono<Void> checkOnce() {
        //before the registry was read every unit would look removed, and its alert be forgotten
        return Mono.justOrEmpty(unitRegistry.current())
            .flatMap(units -> {
                final LocalDateTime now = LocalDateTime.now(clock);
                return alertRepository.findAll()
                    .collectMap(Alert::id, Function.identity())
                    .flatMap(open -> act(AvailabilityEvaluator.decide(watched(units), open, now, properties), now));
            })
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error("The availability check failed: {}",
                failure.toString())));
    }

    private List<Watched> watched(final List<AirConditioner> units) {
        return units.stream()
            .map(unit -> {
                final Optional<UnitState> state = stateStore.of(unit.id());
                return new Watched(unit.id(), unit.name(), Optional.ofNullable(unit.room()).map(RoomName::getValue)
                    .orElse("?"), state.map(UnitState::unreachableSince).orElse(null),
                    state.map(UnitState::reachable).isPresent());
            })
            .toList();
    }

    //the units no longer watched first, then the returns, the alerts and the reminders, each after the one before; the
    //table follows the broker
    private Mono<Void> act(final Decision decision, final LocalDateTime now) {
        return Flux.fromIterable(decision.forgotten())
            .concatMap(alert -> alertRepository.delete(alert.id())
                .doOnSuccess(done -> log.info("Availability alert of air conditioner {} closed: it is no longer watched",
                    alert.id())))
            .then(publish(decision.returns(), notificationPublisher::publishInfo, returnText(decision.returns(), now))
                .flatMap(sent -> Flux.fromIterable(decision.returns()).concatMap(alert -> alertRepository.delete(alert.id()))
                    .then()))
            .then(publish(decision.alerts(), notificationPublisher::publishAlert, alertText("Not answering: ",
                decision.alerts(), now))
                .flatMap(sent -> Flux.fromIterable(decision.alerts())
                    .concatMap(alert -> alertRepository.insert(truncated(alert))).then()))
            .then(publish(decision.reminders(), notificationPublisher::publishReminder, alertText("Still not answering: ",
                decision.reminders(), now))
                .flatMap(sent -> Flux.fromIterable(decision.reminders())
                    .concatMap(alert -> alertRepository.reminded(alert.id(), now.truncatedTo(ChronoUnit.MICROS))).then()));
    }

    //true once the broker took it; empty when there was nothing to say or it failed (logged, tried again)
    private Mono<Boolean> publish(final List<Alert> alerts, final Function<String, Mono<Void>> publisher,
                                  final String text) {
        return Mono.just(alerts)
            .filter(due -> !due.isEmpty())
            .flatMap(due -> publisher.apply(text)
                .timeout(PUBLISH_TIMEOUT)
                .thenReturn(true)
                .doOnNext(sent -> log.info("Availability notification published: {}", text))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                    "Availability notification not published, it is tried again with the next pass: {}",
                    failure.toString()))));
    }

    String alertText(final String lead, final List<Alert> alerts, final LocalDateTime now) {
        return lead + list(alerts, alert -> describe(alert) + " since " + TIME.format(alert.since()) + " ("
            + span(alert.since(), now) + ")") + ".";
    }

    String returnText(final List<Alert> alerts, final LocalDateTime now) {
        return "Answering again: " + list(alerts, alert -> describe(alert) + " - out from " + TIME.format(alert.since())
            + " to " + TIME.format(now) + " (" + span(alert.since(), now) + ")") + ".";
    }

    //by its name and its room - the names of the registry leave the room out ("Air conditioner in office (140)")
    private static String describe(final Alert alert) {
        return alert.name() + " in " + alert.room() + " (" + alert.id() + ")";
    }

    private static String list(final List<Alert> alerts, final Function<Alert, String> line) {
        return alerts.stream().map(line).collect(Collectors.joining("; "));
    }

    //the minutes of the wall clock, as the times the message names
    private static String span(final LocalDateTime since, final LocalDateTime until) {
        final long total = (until.toEpochSecond(ZoneOffset.UTC) - since.toEpochSecond(ZoneOffset.UTC)) / 60;
        return total < 60 ? total + " min" : total / 60 + " h " + total % 60 + " min";
    }

    private static Alert truncated(final Alert alert) {
        return new Alert(alert.id(), alert.name(), alert.room(), alert.since().truncatedTo(ChronoUnit.MICROS),
            alert.lastAlertAt().truncatedTo(ChronoUnit.MICROS));
    }

}
