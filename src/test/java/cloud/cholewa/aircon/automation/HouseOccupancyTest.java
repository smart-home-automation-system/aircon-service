package cloud.cholewa.aircon.automation;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HouseOccupancyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 20, 0);

    @Test
    void should_be_empty_when_every_member_is_away_by_a_fresh_reading() {
        final HouseOccupancy house = HouseOccupancy.judge(List.of(
            away("Krzysztof", NOW.minusHours(2)), away("Liwia", NOW.minusMinutes(30))), NOW);

        assertThat(house.state()).isEqualTo(HouseOccupancy.State.EMPTY);
        assertThat(house.emptySince()).as("the last member to go").isEqualTo(NOW.minusMinutes(30));
        assertThat(house.emptyOrUnknown()).isTrue();
    }

    @Test
    void should_be_occupied_while_one_member_is_at_home() {
        final HouseOccupancy house = HouseOccupancy.judge(List.of(away("Krzysztof", NOW.minusHours(2)),
            new ResidentPresence("Liwia", true, NOW.minusHours(1), NOW)), NOW);

        assertThat(house.state()).isEqualTo(HouseOccupancy.State.OCCUPIED);
        assertThat(house.emptyOrUnknown()).isFalse();
    }

    //a call that failed, no members, a reading too old, a member never observed - never an empty house
    @Test
    void should_not_be_known_without_fresh_readings_of_every_member() {
        assertThat(HouseOccupancy.judge(null, NOW).state()).isEqualTo(HouseOccupancy.State.UNKNOWN);
        assertThat(HouseOccupancy.judge(List.of(), NOW).state()).isEqualTo(HouseOccupancy.State.UNKNOWN);
        assertThat(HouseOccupancy.judge(List.of(new ResidentPresence("Liwia", false, NOW.minusHours(1),
            NOW.minusMinutes(6))), NOW).state()).isEqualTo(HouseOccupancy.State.UNKNOWN);
        assertThat(HouseOccupancy.judge(List.of(new ResidentPresence("Liwia", null, null, null)), NOW).state())
            .isEqualTo(HouseOccupancy.State.UNKNOWN);
        assertThat(HouseOccupancy.unknown().emptyOrUnknown()).isNull();
    }

    @Test
    void should_not_know_since_when_without_the_time_of_every_member() {
        final HouseOccupancy house = HouseOccupancy.judge(List.of(away("Krzysztof", null),
            away("Liwia", NOW.minusMinutes(30))), NOW);

        assertThat(house.empty()).isTrue();
        assertThat(house.emptySince()).isNull();
    }

    private static ResidentPresence away(final String name, final LocalDateTime since) {
        return new ResidentPresence(name, false, since, NOW.minusMinutes(1));
    }
}
