package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.api.model.AutomationReply;
import cloud.cholewa.aircon.automation.AutomationEvaluator.Episode;
import cloud.cholewa.aircon.state.ChangeSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

//The read of the automation (HAS-385): what the last pass knows of the empty house and the grid, from memory - nothing is
//read from presence-service or energy-service for a request
@Service
@RequiredArgsConstructor
public class AutomationService {

    private final AutomationState state;

    public Mono<AutomationReply> automation() {
        return Mono.fromSupplier(() -> {
            final Optional<HouseOccupancy> house = state.house();
            return new AutomationReply(
                new AutomationReply.EmptyHouse(
                    house.map(HouseOccupancy::emptyOrUnknown).orElse(null),
                    house.filter(HouseOccupancy::empty)
                        .map(empty -> AutomationEvaluator.emptyKey(empty, state.firstSeenEmpty().orElse(null)))
                        .orElse(null),
                    state.acted(ChangeSource.EMPTY_HOUSE).map(Episode::at).orElse(null)),
                new AutomationReply.Grid(
                    state.grid().map(GridState::state).orElse(null),
                    state.acted(ChangeSource.GRID_OUTAGE).map(Episode::at).orElse(null)));
        });
    }
}
