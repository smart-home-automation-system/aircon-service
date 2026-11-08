package cloud.cholewa.aircon.api.model;

import cloud.cholewa.aircon.power.PowerCycleRun;
import com.fasterxml.jackson.annotation.JsonInclude;

//Whether the indoor plug may be power cycled now (HAS-390), why not when it may not, and the last cycle (left out before
//the first one)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PowerCycleReply(boolean allowed, String reason, PowerCycleRun lastRun) {
}
