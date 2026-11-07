package cloud.cholewa.aircon.command;

import cloud.cholewa.aircon.state.ChangeSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CommandOutcomesTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 7, 14, 30);

    private final CommandOutcomes outcomes = new CommandOutcomes();

    //a second command before the first was confirmed: the confirmation of the first counts for nothing
    @Test
    void should_keep_only_the_confirmation_of_the_last_command() {
        final long first = outcomes.issue(140);
        final long second = outcomes.issue(140);

        outcomes.confirmed(140, first, false);
        assertThat(outcomes.lastCommandConfirmed(140)).isEmpty();

        outcomes.confirmed(140, second, true);
        assertThat(outcomes.lastCommandConfirmed(140)).contains(true);
    }

    //a new command forgets what the last one came to until its own confirmation
    @Test
    void should_forget_the_last_outcome_at_a_new_command() {
        outcomes.confirmed(140, outcomes.issue(140), true);

        outcomes.issue(140);

        assertThat(outcomes.lastCommandConfirmed(140)).isEmpty();
    }

    //the sender of the change of the next read, taken once and only until its time
    @Test
    void should_give_the_sender_of_the_next_change_once() {
        outcomes.expect(140, ChangeSource.DASHBOARD, AT.plusSeconds(60));

        assertThat(outcomes.takePending(140, AT.plusSeconds(30))).contains(ChangeSource.DASHBOARD);
        assertThat(outcomes.takePending(140, AT.plusSeconds(31))).isEmpty();

        outcomes.expect(141, ChangeSource.RULE, AT.plusSeconds(60));
        assertThat(outcomes.takePending(141, AT.plusSeconds(61))).isEmpty();
    }
}
