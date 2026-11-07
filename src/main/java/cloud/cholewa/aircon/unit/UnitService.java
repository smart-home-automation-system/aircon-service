package cloud.cholewa.aircon.unit;

import cloud.cholewa.aircon.api.model.UnitReply;
import cloud.cholewa.aircon.driver.GreeClient;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

//The units of the registry with one live read of the status of each (HAS-380) - the tracking of their state comes
//with the next task. The units are read at once, each answered in the order of the registry; a unit that does not
//answer within READ_LIMIT is unreachable, so the whole read stays below the response timeout of the gateway (30 s)
@Slf4j
@Service
@RequiredArgsConstructor
public class UnitService {

    //a bind of a unit with a new key (a scan and a bind) and its status, each of them up to three attempts of 3 s,
    //would take longer: such a unit is answered unreachable this time and found bound at the next read
    static final Duration READ_LIMIT = Duration.ofSeconds(25);

    private final UnitRegistry unitRegistry;
    private final GreeClient greeClient;

    public Mono<List<UnitReply>> units() {
        return Mono.justOrEmpty(unitRegistry.current())
            .switchIfEmpty(Mono.error(AirconException::registryUnavailable))
            .flatMapMany(Flux::fromIterable)
            .flatMapSequential(this::read)
            .collectList();
    }

    private Mono<UnitReply> read(final AirConditioner unit) {
        return greeClient.status(unit)
            .timeout(READ_LIMIT)
            .map(status -> new UnitReply(unit.id(), unit.name(), unit.room(), true, status))
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The status of air conditioner {} ({}) could not be read: {}", unit.id(), unit.mac(),
                    failure.getMessage());
                return new UnitReply(unit.id(), unit.name(), unit.room(), false, null);
            }));
    }
}
