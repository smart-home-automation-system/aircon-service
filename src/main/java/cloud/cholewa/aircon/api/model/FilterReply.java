package cloud.cholewa.aircon.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//The filter of a unit in its reads (HAS-391): the hours of work since its cleaning (to a tenth, as the last count of
//every 10 minutes gave them), when it was cleaned (left out before the first cleaning recorded) and whether it is due
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FilterReply(double hoursSince, LocalDateTime cleanedAt, boolean due) {
}
