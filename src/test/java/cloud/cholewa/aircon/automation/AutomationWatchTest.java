package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.api.model.AutomationReply;
import cloud.cholewa.aircon.automation.AutomationEvaluator.Episode;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationWatchTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T19:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");
    private static final AirConditioner SANCTUM = new AirConditioner(144, "Air conditioner", RoomName.SANCTUM,
        "192.0.2.60", "02005e0000a5");
    private static final UnitStatus COOLING = new UnitStatus(true, Mode.COOL, 24, 26, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);
    private static final UnitStatus HEATING = new UnitStatus(true, Mode.HEAT, 21, 19, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);
    private static final UnitStatus OFF = new UnitStatus(false, Mode.COOL, 24, 26, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);
    private static final LocalDateTime WENT = NOW.minusMinutes(15);
    private static final LocalDateTime LOST = NOW.minusMinutes(2);

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final StateStore store = mock(StateStore.class);
    private final PresenceClient presence = mock(PresenceClient.class);
    private final EnergyClient energy = mock(EnergyClient.class);
    private final RuleCommands commands = mock(RuleCommands.class);
    private final RuleRunRepository ruleRuns = mock(RuleRunRepository.class);
    private final EpisodeRepository episodes = mock(EpisodeRepository.class);
    private final AutomationState state = new AutomationState();
    private final AutomationWatch watch = new AutomationWatch(registry, store, presence, energy, commands, ruleRuns,
        episodes, state, new EmptyHouseProperties(Duration.ofMinutes(10), List.of("sanctum")), CLOCK);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, SANCTUM)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));
        when(store.of(144)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));
        when(episodes.all()).thenReturn(Flux.empty());
        when(episodes.save(any(), any())).thenReturn(Mono.empty());
        when(ruleRuns.deleteBefore(any())).thenReturn(Mono.empty());
        when(commands.send(anyLong(), any(), any(), any())).thenReturn(Mono.empty());
        when(presence.residents()).thenReturn(Mono.just(List.of(new ResidentPresence("Krzysztof", true, NOW, NOW))));
        when(energy.grid()).thenReturn(Mono.just(new GridState("present", NOW.minusDays(3))));
    }

    //once, sanctum left alone, the episode stored; nothing switched on at the return
    @Test
    void should_switch_off_the_cooling_units_of_an_empty_house_once() {
        houseEmpty();

        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, times(1)).send(140, AutomationWatch.OFF, ChangeSource.EMPTY_HOUSE, WENT);
        verify(commands, never()).send(eq(144L), any(), any(), any());
        verify(episodes).save(ChangeSource.EMPTY_HOUSE, new Episode(WENT, NOW));
    }

    //a unit a person switches on during the episode is not one of its units
    @Test
    void should_not_fight_a_unit_switched_on_during_the_episode() {
        houseEmpty();
        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), any(), any());
        verify(episodes).save(ChangeSource.EMPTY_HOUSE, new Episode(WENT, NOW));
    }

    //a unit that did not take the command is sent it again the next minute, until it took it
    @Test
    void should_send_again_to_a_unit_that_did_not_take_the_command() {
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));
        when(commands.send(eq(140L), any(), any(), any()))
            .thenReturn(Mono.error(AirconException.deviceFailed("Air conditioner 140 did not take the command")),
                Mono.empty());

        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, times(2)).send(140, AutomationWatch.OFF, ChangeSource.GRID_OUTAGE, LOST);
        verify(commands, times(1)).send(144, AutomationWatch.OFF, ChangeSource.GRID_OUTAGE, LOST);
        assertThat(state.pending(ChangeSource.GRID_OUTAGE)).isEmpty();
    }

    //a unit that does not answer waits; one switched off by anybody needs nothing more
    @Test
    void should_wait_for_a_unit_that_does_not_answer() {
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, false, NOW, NOW)));
        when(store.of(144)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));
        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        assertThat(state.pending(ChangeSource.GRID_OUTAGE)).containsExactly(140L);
        verify(commands, never()).send(eq(140L), any(), any(), any());

        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        assertThat(state.pending(ChangeSource.GRID_OUTAGE)).isEmpty();
        verify(commands, never()).send(eq(140L), any(), any(), any());
    }

    @Test
    void should_act_again_on_the_next_empty_house() {
        houseEmpty();
        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        when(presence.residents()).thenReturn(Mono.just(List.of(new ResidentPresence("Krzysztof", true, NOW, NOW))));
        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        houseEmpty();
        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, times(2)).send(140, AutomationWatch.OFF, ChangeSource.EMPTY_HOUSE, WENT);
    }

    //presence-service silent, or slow: not known, and what is not known never acts
    @Test
    void should_not_act_while_who_is_at_home_is_not_known() {
        when(presence.residents()).thenReturn(Mono.error(new IllegalStateException("presence-service answered 503")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), any(), any());
        assertThat(state.house()).contains(HouseOccupancy.unknown());
    }

    @Test
    void should_switch_off_every_unit_once_for_a_lost_grid() {
        when(store.of(140)).thenReturn(Optional.of(new UnitState(HEATING, true, NOW, null)));
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, times(1)).send(140, AutomationWatch.OFF, ChangeSource.GRID_OUTAGE, LOST);
        verify(commands, times(1)).send(144, AutomationWatch.OFF, ChangeSource.GRID_OUTAGE, LOST);
        verify(episodes).save(ChangeSource.GRID_OUTAGE, new Episode(LOST, NOW));
    }

    //every unit off: the episode is stored all the same, so a restart does not take it for a new one
    @Test
    void should_store_an_episode_without_a_unit_on() {
        when(store.of(anyLong())).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(episodes).save(ChangeSource.GRID_OUTAGE, new Episode(LOST, NOW));
        verify(commands, never()).send(anyLong(), any(), any(), any());
    }

    @Test
    void should_not_act_on_a_grid_not_known() {
        when(energy.grid()).thenReturn(Mono.just(GridState.unknown()));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), any(), any());
    }

    //the episode acted on before a restart is not acted on again
    @Test
    void should_not_act_twice_on_one_outage_across_a_restart() {
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));
        when(episodes.all()).thenReturn(Flux.just(
            new EpisodeRepository.Acted(ChangeSource.GRID_OUTAGE, new Episode(LOST, NOW.minusMinutes(1)))));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), any(), any());
    }

    //the last episodes not read: nothing acts until they are
    @Test
    void should_not_act_before_the_last_episodes_were_read() {
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));
        when(episodes.all()).thenReturn(Flux.error(new IllegalStateException("db")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), any(), any());
    }

    //an episode that could not be stored acts all the same
    @Test
    void should_act_when_the_episode_could_not_be_stored() {
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));
        when(episodes.save(any(), any())).thenReturn(Mono.error(new IllegalStateException("db")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands).send(140, AutomationWatch.OFF, ChangeSource.GRID_OUTAGE, LOST);
    }

    @Test
    void should_go_on_with_the_others_when_one_unit_fails() {
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));
        when(commands.send(eq(140L), any(), any(), any()))
            .thenReturn(Mono.error(AirconException.deviceFailed("Air conditioner 140 did not take the command")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands).send(144, AutomationWatch.OFF, ChangeSource.GRID_OUTAGE, LOST);
    }

    //a command the rules refuse is not sent again
    @Test
    void should_not_send_again_a_command_the_rules_refuse() {
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));
        when(commands.send(eq(140L), any(), any(), any()))
            .thenReturn(Mono.error(AirconException.invalidCommand("refused")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, times(1)).send(140, AutomationWatch.OFF, ChangeSource.GRID_OUTAGE, LOST);
    }

    @Test
    void should_hold_the_units_off_while_the_house_is_empty_or_the_grid_lost() {
        assertThat(state.holdsTheUnitsOff()).isFalse();
        houseEmpty();
        watch.watchOnce().as(StepVerifier::create).verifyComplete();
        assertThat(state.holdsTheUnitsOff()).isTrue();
    }

    @Test
    void should_forget_the_commands_older_than_ninety_days() {
        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(ruleRuns).deleteBefore(NOW.minusDays(90));
    }

    @Test
    void should_answer_what_the_last_pass_knows() {
        houseEmpty();
        when(energy.grid()).thenReturn(Mono.just(new GridState("lost", LOST)));
        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        new AutomationService(state).automation().as(StepVerifier::create)
            .expectNext(new AutomationReply(new AutomationReply.EmptyHouse(true, WENT, NOW),
                new AutomationReply.Grid("lost", NOW)))
            .verifyComplete();
    }

    @Test
    void should_answer_nothing_before_the_first_pass() {
        new AutomationService(new AutomationState()).automation().as(StepVerifier::create)
            .expectNext(new AutomationReply(new AutomationReply.EmptyHouse(null, null, null),
                new AutomationReply.Grid(null, null)))
            .verifyComplete();
    }

    private void houseEmpty() {
        when(presence.residents()).thenReturn(Mono.just(List.of(
            new ResidentPresence("Krzysztof", false, WENT, NOW.minusMinutes(1)),
            new ResidentPresence("Liwia", false, NOW.minusHours(3), NOW.minusMinutes(1)))));
    }
}
