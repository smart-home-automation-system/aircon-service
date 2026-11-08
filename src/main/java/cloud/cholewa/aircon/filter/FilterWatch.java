package cloud.cholewa.aircon.filter;

import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Function;

//The run hours of the filters (HAS-391): every 10 minutes each unit of the registry counted - the time it was on since
//its cleaning (aircon_state_change; the whole history before a cleaning is recorded) - into FilterStore for the reads.
//A filter due (remind-after-hours) is named on Discord as an info, once per step (FilterReminder), the step stored in
//aircon_filter after the broker took it - a restart never repeats one, a failed publish is tried at the next pass. One
//unit after the other; one that fails is a WARN and cuts nothing else. The hours count the time the last read showed the
//unit on: a unit cut from its power while on (a lost grid) counts on until it is read again
@Slf4j
@Component
@RequiredArgsConstructor
public class FilterWatch {

    static final Duration LIMIT = Duration.ofMinutes(5);
    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final UnitRegistry unitRegistry;
    private final FilterRepository repository;
    private final FilterStore store;
    private final FilterProperties properties;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT10M")
    Mono<Void> watch() {
        return ScheduledPass.bounded("air conditioner filters", LIMIT, this::countAll);
    }

    Mono<Void> countAll() {
        return Mono.justOrEmpty(unitRegistry.current())
            .flatMap(units -> repository.filters()
                .collectMap(FilterRecord::unitId, Function.identity())
                .flatMap(filters -> Flux.fromIterable(units)
                    .concatMap(unit -> count(unit, filters))
                    .then()))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The filters of the air conditioners could not be read, tried again in 10 minutes: {}",
                failure.toString())));
    }

    private Mono<Void> count(final AirConditioner unit, final Map<Long, FilterRecord> filters) {
        return Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            final FilterRecord filter = filters.getOrDefault(unit.id(), new FilterRecord(unit.id(), null, null));
            return repository.hours(unit.id(), filter.cleanedAt(), now)
                .doOnNext(hours -> store.put(unit.id(), new FilterState(hours, filter.cleanedAt(),
                    hours >= properties.remindAfterHours())))
                .flatMap(hours -> Mono.justOrEmpty(FilterReminder.step(hours, properties.remindAfterHours(),
                        properties.remindAgainHours(), filter.remindedHours()))
                    .flatMap(step -> remind(unit, filter, hours, step)));
        }).onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
            "The filter of air conditioner {} could not be counted: {}", unit.id(), failure.toString())));
    }

    private Mono<Void> remind(final AirConditioner unit, final FilterRecord filter, final double hours,
                              final double step) {
        final String text = text(unit, filter, hours);
        return notificationPublisher.publishInfo(text)
            .timeout(PUBLISH_TIMEOUT)
            .then(Mono.defer(() -> repository.reminded(unit.id(), step)))
            .doOnSuccess(done -> log.info("Filter reminder published: {}", text))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The filter reminder of air conditioner {} not published, tried again at the next pass: {}", unit.id(),
                failure.toString())));
    }

    //by its name and its room, as the monitor names a unit ("Air conditioner in office (140)")
    static String text(final AirConditioner unit, final FilterRecord filter, final double hours) {
        final String since = filter.cleanedAt() == null
            ? "since its history began (no cleaning recorded)"
            : "since its cleaning on " + DAY.format(filter.cleanedAt());
        return "Clean the filter: " + unit.name() + " in " + roomOf(unit) + " (" + unit.id() + ") has run "
            + Math.round(hours) + " h " + since + ".";
    }

    private static String roomOf(final AirConditioner unit) {
        return unit.room() == null ? "no room" : unit.room().getValue();
    }
}
