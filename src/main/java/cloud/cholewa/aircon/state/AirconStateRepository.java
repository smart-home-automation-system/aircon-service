package cloud.cholewa.aircon.state;

import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;

//The tables of the state of the air conditioners (HAS-381, V2). PostgreSQL only - AirconStateRepositoryTest runs them
//against a PostgreSQL in Docker. A statement each, so a read of a unit holds a connection of the pool of 2 for one round
//trip at a time
@Repository
@RequiredArgsConstructor
public class AirconStateRepository {

    //The current row of a unit: its values and changed_at move only when one of them changed, last_checked_at with every
    //read - the comparison is with the table, so it holds across a restart
    private static final String RECORD_STATE = """
        INSERT INTO aircon_state (unit_id, power, mode, target_temperature, room_temperature, fan_speed, swing_vertical,
                                  swing_horizontal, quiet, turbo, sleep, display, changed_at, last_checked_at)
        VALUES (:unitId, :power, :mode, :targetTemperature, :roomTemperature, :fanSpeed, :swingVertical,
                :swingHorizontal, :quiet, :turbo, :sleep, :display, :at, :at)
        ON CONFLICT (unit_id) DO UPDATE SET
            power = EXCLUDED.power,
            mode = EXCLUDED.mode,
            target_temperature = EXCLUDED.target_temperature,
            room_temperature = EXCLUDED.room_temperature,
            fan_speed = EXCLUDED.fan_speed,
            swing_vertical = EXCLUDED.swing_vertical,
            swing_horizontal = EXCLUDED.swing_horizontal,
            quiet = EXCLUDED.quiet,
            turbo = EXCLUDED.turbo,
            sleep = EXCLUDED.sleep,
            display = EXCLUDED.display,
            changed_at = CASE
                WHEN (aircon_state.power, aircon_state.mode, aircon_state.target_temperature, aircon_state.room_temperature,
                      aircon_state.fan_speed, aircon_state.swing_vertical, aircon_state.swing_horizontal, aircon_state.quiet,
                      aircon_state.turbo, aircon_state.sleep, aircon_state.display)
                     IS DISTINCT FROM (EXCLUDED.power, EXCLUDED.mode, EXCLUDED.target_temperature,
                                       EXCLUDED.room_temperature, EXCLUDED.fan_speed, EXCLUDED.swing_vertical,
                                       EXCLUDED.swing_horizontal, EXCLUDED.quiet, EXCLUDED.turbo, EXCLUDED.sleep,
                                       EXCLUDED.display)
                THEN EXCLUDED.changed_at
                ELSE aircon_state.changed_at
            END,
            last_checked_at = EXCLUDED.last_checked_at
        """;

    private static final String RECORD_CHANGE = """
        INSERT INTO aircon_state_change (unit_id, changed_at, power, mode, target_temperature, fan_speed, source)
        VALUES (:unitId, :at, :power, :mode, :targetTemperature, :fanSpeed, :source)
        """;

    private static final String CURRENT_STATES = """
        SELECT unit_id, power, mode, target_temperature, room_temperature, fan_speed, swing_vertical, swing_horizontal,
               quiet, turbo, sleep, display, last_checked_at
        FROM aircon_state
        """;

    //a bind parameter and a column of the same name
    private static final String POWER = "power";
    private static final String MODE = "mode";

    private final DatabaseClient databaseClient;

    //what a unit reported at a time; a value not reported is NULL
    public Mono<Void> recordState(final long unitId, final UnitStatus status, final LocalDateTime at) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(RECORD_STATE).bind("unitId", unitId).bind("at", at);
        spec = nullable(spec, POWER, status.power(), Boolean.class);
        spec = nullable(spec, MODE, Optional.ofNullable(status.mode()).map(Mode::getValue).orElse(null), String.class);
        spec = nullable(spec, "targetTemperature", status.targetTemperature(), Integer.class);
        spec = nullable(spec, "roomTemperature", status.roomTemperature(), Integer.class);
        spec = nullable(spec, "fanSpeed", Optional.ofNullable(status.fanSpeed()).map(FanSpeed::getValue).orElse(null),
            String.class);
        spec = nullable(spec, "swingVertical", status.swingVertical(), Integer.class);
        spec = nullable(spec, "swingHorizontal", status.swingHorizontal(), Integer.class);
        spec = nullable(spec, "quiet", status.quiet(), Boolean.class);
        spec = nullable(spec, "turbo", status.turbo(), Boolean.class);
        spec = nullable(spec, "sleep", status.sleep(), Boolean.class);
        spec = nullable(spec, "display", status.display(), Boolean.class);
        return spec.fetch().rowsUpdated().then();
    }

    //a change of what a unit does, and who made it
    public Mono<Void> recordChange(final long unitId, final UnitStatus status, final ChangeSource source,
                                   final LocalDateTime at) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(RECORD_CHANGE)
            .bind("unitId", unitId)
            .bind("at", at)
            .bind("source", source.getValue());
        spec = nullable(spec, POWER, status.power(), Boolean.class);
        spec = nullable(spec, MODE, Optional.ofNullable(status.mode()).map(Mode::getValue).orElse(null), String.class);
        spec = nullable(spec, "targetTemperature", status.targetTemperature(), Integer.class);
        spec = nullable(spec, "fanSpeed", Optional.ofNullable(status.fanSpeed()).map(FanSpeed::getValue).orElse(null),
            String.class);
        return spec.fetch().rowsUpdated().then();
    }

    //the current row of every unit ever read; a word of a mode or a fan this version does not know is not known
    public Flux<StoredState> currentStates() {
        return databaseClient.sql(CURRENT_STATES)
            .map(row -> new StoredState(
                row.get("unit_id", Long.class),
                new UnitStatus(
                    row.get(POWER, Boolean.class),
                    text(row, MODE).flatMap(Mode::ofValue).orElse(null),
                    row.get("target_temperature", Integer.class),
                    row.get("room_temperature", Integer.class),
                    text(row, "fan_speed").flatMap(FanSpeed::ofValue).orElse(null),
                    row.get("swing_vertical", Integer.class),
                    row.get("swing_horizontal", Integer.class),
                    row.get("quiet", Boolean.class),
                    row.get("turbo", Boolean.class),
                    row.get("sleep", Boolean.class),
                    row.get("display", Boolean.class),
                    null),
                row.get("last_checked_at", LocalDateTime.class)))
            .all();
    }

    private static Optional<String> text(final Readable row, final String column) {
        return Optional.ofNullable(row.get(column, String.class));
    }

    private static <T> DatabaseClient.GenericExecuteSpec nullable(final DatabaseClient.GenericExecuteSpec spec,
                                                                   final String name, final T value,
                                                                   final Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
