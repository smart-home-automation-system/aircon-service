package cloud.cholewa.aircon.command;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

//What a command may ask of a unit (HAS-382, the owner's rules of 2026-11-04) - a pure class, copied by the dashboard: a
//rule changed here is changed there. The household switches cooling on itself; the service only keeps it from going
//below 22 C. A command is judged against the mode and the target the unit will have after it - what the command sets,
//else what the unit reported
public final class CommandRules {

    public static final int TARGET_MIN = 16;
    public static final int TARGET_MAX = 30;
    public static final int COOLING_MIN = 22;
    public static final int SWING_VERTICAL_MAX = 11;
    public static final int SWING_HORIZONTAL_MAX = 6;

    private static final int CELSIUS = 0;

    private CommandRules() {
    }

    //the reason a command is refused, in English (INVALID_COMMAND carries it), or nothing for a command that may be sent;
    //current is what the unit last reported, null for a unit never read
    public static Optional<String> problem(final CommandRequest command, final UnitStatus current) {
        final Mode mode = Optional.ofNullable(command.mode()).orElse(current == null ? null : current.mode());
        final Integer target = Optional.ofNullable(command.targetTemperature())
            .orElse(current == null ? null : current.targetTemperature());
        if (wire(command).isEmpty()) {
            return Optional.of("A command sets at least one field");
        }
        if (command.targetTemperature() != null
            && (command.targetTemperature() < TARGET_MIN || command.targetTemperature() > TARGET_MAX)) {
            return Optional.of("The target temperature is " + TARGET_MIN + " to " + TARGET_MAX + " C, not "
                + command.targetTemperature());
        }
        //judged only when the command sets the mode or the target: a unit already below the minimum is raised by the
        //guard of the reads, and a change of its fan alone is no reason to refuse
        if ((command.mode() != null || command.targetTemperature() != null) && mode == Mode.COOL && target != null
            && target < COOLING_MIN) {
            return Optional.of("Cooling never goes below " + COOLING_MIN + " C, not " + target);
        }
        if ((Boolean.TRUE.equals(command.turbo()) || Boolean.TRUE.equals(command.quiet()))
            && (mode == Mode.DRY || mode == Mode.FAN)) {
            return Optional.of("Turbo and quiet are not for the mode " + mode.getValue());
        }
        if (outside(command.swingVertical(), SWING_VERTICAL_MAX)) {
            return Optional.of("The vertical swing is 0 to " + SWING_VERTICAL_MAX + ", not " + command.swingVertical());
        }
        if (outside(command.swingHorizontal(), SWING_HORIZONTAL_MAX)) {
            return Optional.of("The horizontal swing is 0 to " + SWING_HORIZONTAL_MAX + ", not "
                + command.swingHorizontal());
        }
        return Optional.empty();
    }

    //the columns of the protocol a command sets, in a fixed order; the target goes with its unit (TemUn 0, degrees C)
    public static Map<String, Integer> wire(final CommandRequest command) {
        final Map<String, Integer> wire = new LinkedHashMap<>();
        put(wire, "Pow", flag(command.power()));
        put(wire, "Mod", Optional.ofNullable(command.mode()).map(Mode::getWire).orElse(null));
        put(wire, "SetTem", command.targetTemperature());
        put(wire, "TemUn", command.targetTemperature() == null ? null : CELSIUS);
        put(wire, "WdSpd", command.fanSpeed() == null ? null : command.fanSpeed().getWire());
        put(wire, "SwUpDn", command.swingVertical());
        put(wire, "SwingLfRig", command.swingHorizontal());
        put(wire, "Quiet", flag(command.quiet()));
        put(wire, "Tur", flag(command.turbo()));
        put(wire, "SwhSlp", flag(command.sleep()));
        put(wire, "Lig", flag(command.display()));
        return wire;
    }

    //whether a read of the unit shows every field the command set
    public static boolean takenBy(final CommandRequest command, final UnitStatus read) {
        return same(command.power(), read, UnitStatus::power)
            && same(command.mode(), read, UnitStatus::mode)
            && same(command.targetTemperature(), read, UnitStatus::targetTemperature)
            && same(command.fanSpeed(), read, UnitStatus::fanSpeed)
            && same(command.swingVertical(), read, UnitStatus::swingVertical)
            && same(command.swingHorizontal(), read, UnitStatus::swingHorizontal)
            && same(command.quiet(), read, UnitStatus::quiet)
            && same(command.turbo(), read, UnitStatus::turbo)
            && same(command.sleep(), read, UnitStatus::sleep)
            && same(command.display(), read, UnitStatus::display);
    }

    private static <T> boolean same(final T asked, final UnitStatus read, final Function<UnitStatus, T> field) {
        return asked == null || Objects.equals(asked, field.apply(read));
    }

    private static boolean outside(final Integer value, final int max) {
        return value != null && (value < 0 || value > max);
    }

    private static Integer flag(final Boolean value) {
        return Optional.ofNullable(value).map(on -> Boolean.TRUE.equals(on) ? 1 : 0).orElse(null);
    }

    private static void put(final Map<String, Integer> wire, final String column, final Integer value) {
        if (value != null) {
            wire.put(column, value);
        }
    }
}
