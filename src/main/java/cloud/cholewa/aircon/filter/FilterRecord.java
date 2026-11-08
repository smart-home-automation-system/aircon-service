package cloud.cholewa.aircon.filter;

import java.time.LocalDateTime;

//A row of aircon_filter (HAS-391): when the filter of a unit was last cleaned (null before the first cleaning recorded)
//and the hours of work the last reminder was sent for (null before the first, and after a cleaning)
public record FilterRecord(long unitId, LocalDateTime cleanedAt, Double remindedHours) {
}
