package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.state.ChangeSource;

import java.time.LocalDateTime;

//An automatic command, a row of aircon_rule_run (HAS-385): when, by whom, to which unit, on which episode (null when not
//known), the command in the words of the wire, and what it came to
public record RuleRun(LocalDateTime at, ChangeSource source, long unitId, LocalDateTime episode, String command,
                      RunResult result) {
}
