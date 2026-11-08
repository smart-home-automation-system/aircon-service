package cloud.cholewa.aircon.power;

import cloud.cholewa.aircon.api.model.PlugReply;
import cloud.cholewa.aircon.api.model.PowerCycleReply;
import cloud.cholewa.aircon.error.AirconException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

//The reads of the plugs (HAS-390): the draw now from PlugStore, the energy from aircon_power_sample (today) and
//aircon_power_day (the closed days), the history in buckets by the rules of HistoryRange, whether a unit's plug feeds a
//running compressor, and the power cycle of the indoor plug. The energy that cannot be read from the database is left
//out of the answer (a WARN), the draw is answered all the same
@Slf4j
@Service
@RequiredArgsConstructor
public class PowerService {

    private final PlugProperties properties;
    private final PlugStore plugStore;
    private final PowerRepository repository;
    private final PowerCycle powerCycle;
    private final Clock clock;

    public Mono<List<PlugReply>> plugs() {
        return Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            return Flux.fromIterable(Arrays.asList(PlugName.values()))
                .concatMap(plug -> energy(plug, now).map(energy -> replyOf(plug, now, energy)))
                .collectList();
        });
    }

    public Mono<List<PowerBucket>> samples(final String plug, final LocalDateTime from, final LocalDateTime to) {
        return Mono.justOrEmpty(PlugName.ofValue(plug))
            .switchIfEmpty(Mono.error(AirconException::plugNotFound))
            .flatMap(name -> Mono.justOrEmpty(HistoryRange.violation(from, to))
                .flatMap(Mono::<PlugName>error)
                .defaultIfEmpty(name))
            .flatMap(name -> repository.buckets(name, from, to, HistoryRange.bucket(from, to).toSeconds()).collectList());
    }

    //whether the plug of the room feeds a running compressor - above the standby; null while its draw is not known or no
    //plug feeds the room. The indoor plug feeds four units: true says one of them runs
    public Boolean compressorRunning(final String room) {
        final LocalDateTime now = LocalDateTime.now(clock);
        return Arrays.stream(PlugName.values())
            .filter(plug -> room != null && properties.of(plug).rooms().contains(room))
            .findFirst()
            .flatMap(plug -> plugStore.fresh(plug, now))
            .map(reading -> reading.power() > properties.standbyWatts())
            .orElse(null);
    }

    public Mono<PowerCycleReply> powerCycle() {
        return powerCycle.lastRun()
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The last power cycle could not be read: {}", failure.toString());
                return Optional.empty();
            }))
            .map(last -> {
                final Optional<String> refusal = powerCycle.refusal();
                return new PowerCycleReply(refusal.isEmpty(), refusal.orElse(null), last.orElse(null));
            });
    }

    public Mono<Void> startPowerCycle(final String requestedBy) {
        return powerCycle.start(requestedBy);
    }

    private PlugReply replyOf(final PlugName plug, final LocalDateTime now, final Energy energy) {
        final PlugProperties.Plug configured = properties.of(plug);
        final Optional<PlugReading> fresh = plugStore.fresh(plug, now);
        final Optional<PlugReading> last = plugStore.last(plug);
        return new PlugReply(
            plug.getValue(),
            configured.rooms(),
            configured.meter() == PlugProperties.Meter.SWITCH,
            fresh.map(PlugReading::power).orElse(null),
            fresh.map(PlugReading::voltage).orElse(null),
            fresh.map(PlugReading::current).orElse(null),
            fresh.map(PlugReading::output).orElse(null),
            fresh.map(reading -> reading.power() > properties.standbyWatts()).orElse(null),
            reachable(plug, last),
            last.map(PlugReading::at).orElse(null),
            energy.today(),
            energy.last7Days(),
            energy.last30Days());
    }

    private Boolean reachable(final PlugName plug, final Optional<PlugReading> last) {
        if (plugStore.failingSince(plug).isPresent()) {
            return false;
        }
        return last.isPresent() ? true : null;
    }

    private Mono<Energy> energy(final PlugName plug, final LocalDateTime now) {
        final LocalDate today = now.toLocalDate();
        //one statement after the other: the pool of the service has two connections, shared with every pass
        return repository.energy(plug, today.atStartOfDay(), now)
            .flatMap(sinceMidnight -> repository.days(plug, today.minusDays(7), today)
                .flatMap(week -> repository.days(plug, today.minusDays(30), today)
                    .map(month -> new Energy(sinceMidnight.orElse(null), week.orElse(null), month.orElse(null)))))
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The energy of the plug {} could not be read: {}", plug.getValue(), failure.toString());
                return new Energy(null, null, null);
            }));
    }

    private record Energy(Double today, Double last7Days, Double last30Days) {
    }
}
