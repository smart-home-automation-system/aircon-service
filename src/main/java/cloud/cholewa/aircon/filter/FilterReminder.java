package cloud.cholewa.aircon.filter;

import java.util.Optional;

//When the filter of a unit is named on Discord, HAS-391 - pure: at remindAfterHours of work since its cleaning and again
//at every remindAgainHours more, so 200, 250, 300 and on. The answer is the step reached, remembered once the info is
//out, and nothing while that step was named already or the filter is not due. A pass that missed steps, with the
//service down for days, names only the last one reached
final class FilterReminder {

    private FilterReminder() {
    }

    static Optional<Double> step(final double hours, final int after, final int again, final Double reminded) {
        if (hours < after) {
            return Optional.empty();
        }
        final double step = after + Math.floor((hours - after) / again) * again;
        return reminded == null || step > reminded ? Optional.of(step) : Optional.empty();
    }
}
