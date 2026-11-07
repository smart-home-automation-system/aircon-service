package cloud.cholewa.aircon.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//GET /home/aircon/automation (HAS-385): the empty house and the grid as the last pass of the automation read them, and
//when each last switched the units off. A field not known is left out - both objects are empty before the first pass
//@param emptyHouse empty - whether nobody is at home (left out while not known); since - since when, the last member to
//                  go (or the first pass that saw it); actedAt - when the units were switched off for this empty house
//@param grid       state - present or lost as energy-service says it (left out while not known); actedAt - when the
//                  units were switched off for this outage
public record AutomationReply(EmptyHouse emptyHouse, Grid grid) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmptyHouse(Boolean empty, LocalDateTime since, LocalDateTime actedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Grid(String state, LocalDateTime actedAt) {
    }
}
