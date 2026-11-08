package cloud.cholewa.aircon.power;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//A power cycle as stored (HAS-390): when it started and ended (left out while running), who asked (left out when nobody
//was named) and what it came to
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PowerCycleRun(LocalDateTime startedAt, LocalDateTime finishedAt, String requestedBy, CycleResult result) {
}
