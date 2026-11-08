package cloud.cholewa.aircon.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterReminderTest {

    @Test
    void should_name_a_filter_at_two_hundred_hours_and_every_fifty_more() {
        assertThat(FilterReminder.step(199.9, 200, 50, null)).isEmpty();
        assertThat(FilterReminder.step(200.0, 200, 50, null)).contains(200.0);
        assertThat(FilterReminder.step(249.9, 200, 50, 200.0)).as("named at 200 already").isEmpty();
        assertThat(FilterReminder.step(250.0, 200, 50, 200.0)).contains(250.0);
        assertThat(FilterReminder.step(310.0, 200, 50, 250.0)).contains(300.0);
    }

    //the service down for days: the last step reached alone, never every step missed
    @Test
    void should_name_only_the_last_step_reached() {
        assertThat(FilterReminder.step(420.0, 200, 50, null)).contains(400.0);
        assertThat(FilterReminder.step(420.0, 200, 50, 400.0)).isEmpty();
    }
}
