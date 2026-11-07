package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.automation.AutomationEvaluator.Episode;
import cloud.cholewa.aircon.state.ChangeSource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

//What the automation knows (HAS-385), in memory: the house and the grid as the last pass read them, since when this
//service first saw the house empty (the key of an empty house presence-service gives no time for), the episode the empty
//house and a lost grid last acted on - restored from aircon_automation_episode at a start, forgotten when the episode
//ends (somebody at home, the grid present) - and the units each still has to switch off (in memory only: a restart in
//the middle of an episode leaves the ones not yet taken). Written by the automation pass only; the window pass asks it
//whether a unit may be switched back on
@Component
public class AutomationState {

    private final AtomicReference<HouseOccupancy> house = new AtomicReference<>();
    private final AtomicReference<GridState> grid = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> firstSeenEmpty = new AtomicReference<>();
    private final Map<ChangeSource, Episode> acted = new ConcurrentHashMap<>();
    private final Map<ChangeSource, Set<Long>> pending = new ConcurrentHashMap<>();

    public Optional<HouseOccupancy> house() {
        return Optional.ofNullable(house.get());
    }

    public Optional<GridState> grid() {
        return Optional.ofNullable(grid.get());
    }

    public Optional<LocalDateTime> firstSeenEmpty() {
        return Optional.ofNullable(firstSeenEmpty.get());
    }

    public Optional<Episode> acted(final ChangeSource source) {
        return Optional.ofNullable(acted.get(source));
    }

    public Set<Long> pending(final ChangeSource source) {
        return Set.copyOf(pending.getOrDefault(source, Set.of()));
    }

    //nobody at home or the grid lost, as the last pass read them: no unit is switched back on then (the window, HAS-384)
    public boolean holdsTheUnitsOff() {
        return house().map(HouseOccupancy::empty).orElse(false) || grid().map(GridState::lost).orElse(false);
    }

    //a read of the house: an empty house remembered from the first pass that saw it, forgotten once somebody is at home;
    //a house not known changes neither
    void house(final HouseOccupancy read, final LocalDateTime now) {
        house.set(read);
        if (read.state() == HouseOccupancy.State.EMPTY) {
            firstSeenEmpty.compareAndSet(null, now);
        } else if (read.state() == HouseOccupancy.State.OCCUPIED) {
            firstSeenEmpty.set(null);
            ended(ChangeSource.EMPTY_HOUSE);
        }
    }

    //a read of the grid: an outage ends when the grid is present; a grid not known changes nothing
    void grid(final GridState read) {
        grid.set(read);
        if (read.present()) {
            ended(ChangeSource.GRID_OUTAGE);
        }
    }

    //an episode begins: the units it has to switch off
    void begins(final ChangeSource source, final Episode episode, final Collection<Long> units) {
        acted.put(source, episode);
        final Set<Long> left = ConcurrentHashMap.newKeySet();
        left.addAll(units);
        pending.put(source, left);
    }

    //a unit that needs nothing more of the episode
    void done(final ChangeSource source, final long unitId) {
        Optional.ofNullable(pending.get(source)).ifPresent(left -> left.remove(unitId));
    }

    private void ended(final ChangeSource source) {
        acted.remove(source);
        pending.remove(source);
    }

    //the episodes of aircon_automation_episode at a start - one the pass has acted on since is kept
    void restore(final ChangeSource source, final Episode episode) {
        acted.putIfAbsent(source, episode);
    }
}
