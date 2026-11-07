package cloud.cholewa.aircon.automation;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

//Whether anybody of the household is at home, as presence-service says it (HAS-385; judge is HouseState.judge of
//light-service, HAS-263, through its copy in water-service - the fourth copy). Unknown is never empty. Since HAS-385 also
//since when the house is empty: the last of the members to go - the key of an episode that survives a restart
public record HouseOccupancy(State state, LocalDateTime emptySince) {

    public enum State { EMPTY, OCCUPIED, UNKNOWN }

    //presence-service checks the household every minute: a reading older than this is not a reading
    static final Duration FRESH = Duration.ofMinutes(5);

    public static HouseOccupancy unknown() {
        return new HouseOccupancy(State.UNKNOWN, null);
    }

    //empty: every active member is away, each by a reading newer than FRESH; a member presence-service has never
    //observed is not away; no members at all, or a call that failed (null), is not known
    public static HouseOccupancy judge(final List<ResidentPresence> residents, final LocalDateTime now) {
        if (residents == null || residents.isEmpty()) {
            return unknown();
        }
        if (residents.stream().anyMatch(resident -> Boolean.TRUE.equals(resident.present()))) {
            return new HouseOccupancy(State.OCCUPIED, null);
        }
        final boolean everyAwayAndFresh = residents.stream().allMatch(resident -> Boolean.FALSE.equals(resident.present())
            && resident.lastCheckedAt() != null && !resident.lastCheckedAt().isBefore(now.minus(FRESH)));
        return everyAwayAndFresh ? new HouseOccupancy(State.EMPTY, lastToGo(residents)) : unknown();
    }

    //when the last member went; not known when one of them has no time
    private static LocalDateTime lastToGo(final List<ResidentPresence> residents) {
        return residents.stream().map(ResidentPresence::since).anyMatch(Objects::isNull)
            ? null
            : residents.stream().map(ResidentPresence::since).max(LocalDateTime::compareTo).orElse(null);
    }

    public boolean empty() {
        return state == State.EMPTY;
    }

    //whether the house is empty: null while not known
    public Boolean emptyOrUnknown() {
        return switch (state) {
            case EMPTY -> Boolean.TRUE;
            case OCCUPIED -> Boolean.FALSE;
            case UNKNOWN -> null;
        };
    }
}
