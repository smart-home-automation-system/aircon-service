package cloud.cholewa.aircon.driver;

import cloud.cholewa.aircon.driver.wire.GreeReply;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

//The status of a unit from its "dat" answer (HAS-380): the values come by the index of the columns asked for. The wire
//stays here - the rest of the service sees UnitStatus. A column the unit left out is null; a value that is not the
//model (a mode or a fan the protocol does not have, text where a number belongs, more values than columns) fails the
//whole answer - a status half believed is worse than none
final class GreeStatusMapper {

    //the columns a status asks for: the state, the room temperature (TemSen, the reading + 40) and the firmware (hid)
    static final List<String> COLUMNS = List.of("Pow", "Mod", "SetTem", "TemUn", "WdSpd", "SwUpDn", "SwingLfRig",
        "Quiet", "Tur", "SwhSlp", "Lig", "TemSen", "hid");

    private static final int TEM_SEN_OFFSET = 40;
    private static final int CELSIUS = 0;

    private GreeStatusMapper() {
    }

    static UnitStatus map(final GreeReply reply, final String mac) {
        if (!"dat".equals(reply.t()) || !GreeReplies.succeeded(reply) || !GreeReplies.isOf(reply, mac)
            || reply.cols() == null || reply.dat() == null || reply.cols().size() != reply.dat().size()) {
            throw new GreeCallException("The status of the unit " + mac + " is not the model");
        }
        final Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < reply.cols().size(); index++) {
            values.put(reply.cols().get(index), reply.dat().get(index));
        }
        final Integer unit = number(values, "TemUn", mac);
        final Integer temSen = number(values, "TemSen", mac);
        return new UnitStatus(
            flag(values, "Pow", mac),
            enumOf(values, "Mod", mac, Mode::ofWire),
            unit == null || unit == CELSIUS ? number(values, "SetTem", mac) : null,
            temSen == null || temSen == 0 ? null : temSen - TEM_SEN_OFFSET,
            enumOf(values, "WdSpd", mac, FanSpeed::ofWire),
            number(values, "SwUpDn", mac),
            number(values, "SwingLfRig", mac),
            flag(values, "Quiet", mac),
            flag(values, "Tur", mac),
            flag(values, "SwhSlp", mac),
            flag(values, "Lig", mac),
            text(values, "hid", mac)
        );
    }

    private static Integer number(final Map<String, Object> values, final String column, final String mac) {
        final Object value = values.get(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number;
        }
        throw new GreeCallException("The column " + column + " of the unit " + mac + " is not a number");
    }

    //0 is off; any other number on - Quiet is 2 for its automatic setting on some firmware
    private static Boolean flag(final Map<String, Object> values, final String column, final String mac) {
        return Optional.ofNullable(number(values, column, mac)).map(number -> number != 0).orElse(null);
    }

    private static <T> T enumOf(final Map<String, Object> values, final String column, final String mac,
                                final IntFunction<Optional<T>> ofWire) {
        return Optional.ofNullable(number(values, column, mac))
            .map(number -> ofWire.apply(number).orElseThrow(() ->
                new GreeCallException("The column " + column + " of the unit " + mac + " has the unknown value " + number)))
            .orElse(null);
    }

    private static String text(final Map<String, Object> values, final String column, final String mac) {
        final Object value = values.get(column);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new GreeCallException("The column " + column + " of the unit " + mac + " is not a text");
    }
}
