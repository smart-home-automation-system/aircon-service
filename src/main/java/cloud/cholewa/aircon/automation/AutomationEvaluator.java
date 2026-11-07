package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.home.model.RoomName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

//When the empty house and a lost grid switch the units off, and which (HAS-385) - pure. An episode is known by its key
//(when the house emptied, when the grid was lost); when one begins, the units it switches off are taken once - the units
//on then - and each is sent the command until it took it, while the episode lasts; a unit switched on later, by a person,
//is never one of them, so nobody is fought, and nothing is switched back on at the return. What is not known never acts:
//an unknown house is not empty, an unknown grid is not lost
public final class AutomationEvaluator {

    //the modes that cool the house - the empty house leaves heating alone
    static final Set<Mode> COOLING = Set.of(Mode.COOL, Mode.DRY, Mode.FAN, Mode.AUTO);

    private AutomationEvaluator() {
    }

    //the episode a source acted on last: its key (null when it was not known) and when it began
    public record Episode(LocalDateTime key, LocalDateTime at) {
    }

    //the key of the empty house: when the last member went, or - not known - when this service first saw it empty
    public static LocalDateTime emptyKey(final HouseOccupancy house, final LocalDateTime firstSeenEmpty) {
        return Optional.ofNullable(house.emptySince()).orElse(firstSeenEmpty);
    }

    //an empty house begins: empty for after since its key, and not acted on yet
    public static boolean emptyHouseBegins(final HouseOccupancy house, final LocalDateTime firstSeenEmpty,
                                           final Episode acted, final LocalDateTime now, final Duration after) {
        final LocalDateTime key = emptyKey(house, firstSeenEmpty);
        return house.empty() && key != null && !now.isBefore(key.plus(after)) && notActedOn(acted, key);
    }

    public static boolean outageBegins(final GridState grid, final Episode acted) {
        return grid.lost() && notActedOn(acted, grid.since());
    }

    private static boolean notActedOn(final Episode acted, final LocalDateTime key) {
        return acted == null || !Objects.equals(acted.key(), key);
    }

    //the units the empty house switches off: on, in a mode that cools, outside the exempt rooms
    public static List<AirConditioner> emptyHouseTargets(final List<AirConditioner> units,
                                                         final Map<Long, UnitStatus> statuses,
                                                         final List<String> exemptRooms) {
        return units.stream()
            .filter(unit -> Optional.ofNullable(unit.room()).map(RoomName::getValue).filter(exemptRooms::contains)
                .isEmpty())
            .filter(unit -> Optional.ofNullable(statuses.get(unit.id()))
                .filter(status -> Boolean.TRUE.equals(status.power()) && COOLING.contains(status.mode()))
                .isPresent())
            .toList();
    }

    //the units a lost grid switches off: every unit that is on, heating included, in every room
    public static List<AirConditioner> gridTargets(final List<AirConditioner> units, final Map<Long, UnitStatus> statuses) {
        return units.stream()
            .filter(unit -> Optional.ofNullable(statuses.get(unit.id()))
                .filter(status -> Boolean.TRUE.equals(status.power()))
                .isPresent())
            .toList();
    }

    //what a unit still to switch off asks for now: the command while it is on and answering; nothing more once it is
    //off (taken, or switched off by anybody); waiting while it does not answer or is not known
    public enum Pending { SEND, DONE, WAIT }

    public static Pending pending(final UnitState state) {
        if (state == null || state.status() == null) {
            return Pending.WAIT;
        }
        if (!Boolean.TRUE.equals(state.status().power())) {
            return Pending.DONE;
        }
        return Boolean.FALSE.equals(state.reachable()) ? Pending.WAIT : Pending.SEND;
    }
}
