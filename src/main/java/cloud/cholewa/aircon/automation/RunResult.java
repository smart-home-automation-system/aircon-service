package cloud.cholewa.aircon.automation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

//What an automatic command came to (HAS-385) - the result column of aircon_rule_run, its words pinned by its CHECK: the
//unit took the bytes, it did not answer (it may have taken it all the same), or the rules of the commands refuse it
@Getter
@RequiredArgsConstructor
public enum RunResult {
    SENT("sent"),
    FAILED("failed"),
    REFUSED("refused");

    private final String value;
}
