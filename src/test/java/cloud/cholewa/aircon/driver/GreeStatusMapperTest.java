package cloud.cholewa.aircon.driver;

import cloud.cholewa.aircon.driver.wire.GreeReply;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GreeStatusMapperTest {

    private static final String MAC = "02005e0000a1";

    @Test
    void should_read_every_column_by_its_index() {
        final UnitStatus status = GreeStatusMapper.map(dat(List.of(0, 4, 21, 0, 0, 3, 2, 2, 1, 1, 0, 59, "hid")), MAC);

        assertThat(status).isEqualTo(new UnitStatus(false, Mode.HEAT, 21, 19, FanSpeed.AUTO, 3, 2, true, true, true,
            false, "hid"));
    }

    //a column the unit left out is not known - never off
    @Test
    void should_leave_out_what_the_unit_did_not_report() {
        final GreeReply reply = new GreeReply("dat", MAC, null, 200, null, List.of("Pow"), List.of(1), null, null, null);

        assertThat(GreeStatusMapper.map(reply, MAC))
            .isEqualTo(new UnitStatus(true, null, null, null, null, null, null, null, null, null, null, null));
    }

    //TemSen 0 is a unit without a sensor, not -40 degrees
    @Test
    void should_know_no_room_temperature_without_a_sensor() {
        assertThat(GreeStatusMapper.map(dat(List.of(1, 1, 22, 0, 0, 0, 0, 0, 0, 0, 0, 0, "hid")), MAC).roomTemperature())
            .isNull();
    }

    //the target is in degrees C only while TemUn says so
    @Test
    void should_know_no_target_in_fahrenheit() {
        assertThat(GreeStatusMapper.map(dat(List.of(1, 1, 72, 1, 0, 0, 0, 0, 0, 0, 0, 64, "hid")), MAC)
            .targetTemperature()).isNull();
    }

    @Test
    void should_refuse_a_mode_or_a_fan_the_protocol_does_not_have() {
        assertThatThrownBy(() -> GreeStatusMapper.map(dat(List.of(1, 7, 22, 0, 0, 0, 0, 0, 0, 0, 0, 64, "hid")), MAC))
            .hasMessage("The column Mod of the unit 02005e0000a1 has the unknown value 7");
        assertThatThrownBy(() -> GreeStatusMapper.map(dat(List.of(1, 1, 22, 0, 6, 0, 0, 0, 0, 0, 0, 64, "hid")), MAC))
            .hasMessage("The column WdSpd of the unit 02005e0000a1 has the unknown value 6");
    }

    @Test
    void should_refuse_a_value_of_another_type() {
        assertThatThrownBy(() -> GreeStatusMapper.map(dat(List.of("on", 1, 22, 0, 0, 0, 0, 0, 0, 0, 0, 64, "hid")), MAC))
            .hasMessage("The column Pow of the unit 02005e0000a1 is not a number");
        assertThatThrownBy(() -> GreeStatusMapper.map(dat(List.of(1, 1, 22, 0, 0, 0, 0, 0, 0, 0, 0, 64, 5)), MAC))
            .hasMessage("The column hid of the unit 02005e0000a1 is not a text");
    }

    @Test
    void should_refuse_an_answer_that_is_not_a_status_of_this_unit() {
        final List<Object> values = List.of(1, 1, 22, 0, 0, 0, 0, 0, 0, 0, 0, 64, "hid");
        assertThatThrownBy(() -> GreeStatusMapper.map(new GreeReply("res", MAC, null, 200, null,
            GreeStatusMapper.COLUMNS, values, null, null, null), MAC)).isInstanceOf(GreeCallException.class);
        assertThatThrownBy(() -> GreeStatusMapper.map(new GreeReply("dat", "02005e0000ff", null, 200, null,
            GreeStatusMapper.COLUMNS, values, null, null, null), MAC)).isInstanceOf(GreeCallException.class);
        assertThatThrownBy(() -> GreeStatusMapper.map(new GreeReply("dat", MAC, null, 400, null,
            GreeStatusMapper.COLUMNS, values, null, null, null), MAC)).isInstanceOf(GreeCallException.class);
        assertThatThrownBy(() -> GreeStatusMapper.map(dat(List.of(1, 1)), MAC))
            .hasMessage("The status of the unit 02005e0000a1 is not the model");
    }

    @Test
    void should_never_print_the_key_of_a_reply() {
        assertThat(new GreeReply("bindok", MAC, null, 200, FakeGreeUnit.KEY, null, null, null, null, null).toString())
            .doesNotContain(FakeGreeUnit.KEY);
    }

    private static GreeReply dat(final List<Object> values) {
        return new GreeReply("dat", MAC, null, 200, null, GreeStatusMapper.COLUMNS, values, null, null, null);
    }
}
