package cloud.cholewa.aircon.unit;

import cloud.cholewa.aircon.api.model.AirconReply;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

//The reads of the air conditioners (HAS-381): the units of the registry with the state the tracker keeps - nothing is
//read from a unit for a request any more. A unit the tracker has not read yet answers its id, name and room alone
@Service
@RequiredArgsConstructor
public class UnitService {

    private final UnitRegistry unitRegistry;
    private final StateStore stateStore;

    public Mono<List<AirconReply>> units() {
        return registered().map(units -> units.stream().map(this::replyOf).toList());
    }

    public Mono<AirconReply> unit(final long id) {
        return registered()
            .flatMap(units -> Mono.justOrEmpty(units.stream().filter(unit -> unit.id() == id).findFirst()))
            .map(this::replyOf)
            .switchIfEmpty(Mono.error(() -> AirconException.airconNotFound(id)));
    }

    public Mono<List<AirconReply>> room(final String room) {
        return registered()
            .map(units -> units.stream()
                .filter(unit -> unit.room() != null && unit.room().getValue().equals(room))
                .map(this::replyOf)
                .toList())
            .filter(replies -> !replies.isEmpty())
            .switchIfEmpty(Mono.error(AirconException::roomNotFound));
    }

    private Mono<List<AirConditioner>> registered() {
        return Mono.justOrEmpty(unitRegistry.current())
            .switchIfEmpty(Mono.error(AirconException::registryUnavailable));
    }

    private AirconReply replyOf(final AirConditioner unit) {
        final Optional<UnitState> state = stateStore.of(unit.id());
        final Optional<UnitStatus> status = state.map(UnitState::status);
        return new AirconReply(
            unit.id(),
            unit.name(),
            unit.room(),
            status.map(UnitStatus::power).orElse(null),
            status.map(UnitStatus::mode).orElse(null),
            status.map(UnitStatus::targetTemperature).orElse(null),
            status.map(UnitStatus::roomTemperature).orElse(null),
            status.map(UnitStatus::fanSpeed).orElse(null),
            status.map(UnitStatus::swingVertical).orElse(null),
            status.map(UnitStatus::swingHorizontal).orElse(null),
            status.map(UnitStatus::quiet).orElse(null),
            status.map(UnitStatus::turbo).orElse(null),
            status.map(UnitStatus::sleep).orElse(null),
            status.map(UnitStatus::display).orElse(null),
            state.map(UnitState::reachable).orElse(null),
            state.map(UnitState::updatedAt).orElse(null));
    }
}
