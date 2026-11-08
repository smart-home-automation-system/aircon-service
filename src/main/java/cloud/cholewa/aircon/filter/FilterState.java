package cloud.cholewa.aircon.filter;

import java.time.LocalDateTime;

//The filter of a unit as the last pass counted it (HAS-391): the hours of work since its cleaning, when it was cleaned
//(null before the first cleaning recorded) and whether it is due - remind-after-hours or more
public record FilterState(double hoursSince, LocalDateTime cleanedAt, boolean due) {
}
