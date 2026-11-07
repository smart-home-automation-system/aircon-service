package cloud.cholewa.aircon.registry;

import cloud.cholewa.aircon.driver.GreeClient;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.home.model.SmartDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The air conditioners of the device registry (HAS-380), read at the start and every five minutes; a unit not bound yet
//is bound right after the read, so the first request of the API finds its key. A failed read is a WARN and keeps what
//was read before; until the first read succeeds nothing is known (the API answers REGISTRY_UNAVAILABLE). A switched-off
//device is left out; one the service does not understand is a WARN of every read - the registry never stores one.
//A unit that does not bind is a WARN and is bound again at its first request
@Slf4j
@Component
@RequiredArgsConstructor
public class UnitRegistry {

    //below the delay of five minutes: a read of the registry, then a scan and a bind of every unit not bound yet, each
    //of them up to 1 + retries attempts of the timeout
    static final Duration LIMIT = Duration.ofMinutes(2);

    private final DeviceRegistryClient deviceRegistryClient;
    private final GreeClient greeClient;
    private final AtomicReference<List<AirConditioner>> units = new AtomicReference<>();

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("air conditioner registry refresh", LIMIT, () -> deviceRegistryClient.getAirConditioners()
            .map(UnitRegistry::understood)
            .doOnNext(this::replace)
            .flatMapMany(Flux::fromIterable)
            .filter(unit -> !greeClient.isBound(unit))
            .flatMap(unit -> greeClient.bind(unit)
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn("Air conditioner {} ({}) could not be bound: {}",
                    unit.id(), unit.mac(), failure.getMessage()))))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The air conditioners of the registry could not be read, the last ones are kept: {}", failure.toString()))));
    }

    //the air conditioners of the registry, or nothing before the first read succeeded
    public Optional<List<AirConditioner>> current() {
        return Optional.ofNullable(units.get());
    }

    private void replace(final List<AirConditioner> read) {
        final List<AirConditioner> before = units.getAndSet(read);
        if (!read.equals(before)) {
            log.info("The registry knows {} air conditioner(s): {}", read.size(),
                read.stream().map(unit -> unit.id() + " " + unit.mac()).toList());
        }
    }

    private static List<AirConditioner> understood(final List<SmartDevice> devices) {
        return devices.stream()
            .filter(device -> !Boolean.FALSE.equals(device.getEnabled()))
            .map(device -> AirConditioner.of(device).or(() -> {
                log.warn("Device {} of the registry is no air conditioner the service understands", device.getId());
                return Optional.empty();
            }))
            .flatMap(Optional::stream)
            .toList();
    }
}
