package cloud.cholewa.aircon.window;

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
import java.util.Arrays;
import java.util.Optional;

//The stops of the units by an open window (HAS-384, V5), a statement each. DISTINCT ON is PostgreSQL's - tested on a
//PostgreSQL of Testcontainers
@Repository
@RequiredArgsConstructor
public class WindowHoldRepository {

    private static final String LATEST = """
        SELECT DISTINCT ON (unit_id) id, unit_id, window_open_since, held_since, mode, target_temperature, fan_speed,
               swing_vertical, swing_horizontal, quiet, turbo, sleep, closed_at, outcome
        FROM aircon_window_hold
        ORDER BY unit_id, id DESC
        """;

    private static final String INSERT = """
        INSERT INTO aircon_window_hold (unit_id, window_open_since, held_since, mode, target_temperature, fan_speed,
                                        swing_vertical, swing_horizontal, quiet, turbo, sleep)
        VALUES (:unitId, :windowOpenSince, :heldSince, :mode, :targetTemperature, :fanSpeed, :swingVertical,
                :swingHorizontal, :quiet, :turbo, :sleep)
        """;

    private static final String CLOSE = """
        UPDATE aircon_window_hold SET closed_at = :at, outcome = :outcome WHERE id = :id AND closed_at IS NULL
        """;

    private static final String DELETE_CLOSED_BEFORE = """
        DELETE FROM aircon_window_hold WHERE closed_at IS NOT NULL AND closed_at < :before
        """;

    private static final String MODE = "mode";

    private final DatabaseClient databaseClient;

    //the last stop of every unit ever stopped - open or how it ended
    public Flux<WindowHold> latest() {
        return databaseClient.sql(LATEST).map(WindowHoldRepository::holdOf).all();
    }

    //a new open stop; refused by the unique index while the unit has an open one
    public Mono<Void> insert(final long unitId, final LocalDateTime windowOpenSince, final LocalDateTime heldSince,
                             final UnitStatus before) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT)
            .bind("unitId", unitId)
            .bind("heldSince", heldSince);
        spec = nullable(spec, "windowOpenSince", windowOpenSince, LocalDateTime.class);
        spec = nullable(spec, MODE, Optional.ofNullable(before.mode()).map(Mode::getValue).orElse(null), String.class);
        spec = nullable(spec, "targetTemperature", before.targetTemperature(), Integer.class);
        spec = nullable(spec, "fanSpeed", Optional.ofNullable(before.fanSpeed()).map(FanSpeed::getValue).orElse(null),
            String.class);
        spec = nullable(spec, "swingVertical", before.swingVertical(), Integer.class);
        spec = nullable(spec, "swingHorizontal", before.swingHorizontal(), Integer.class);
        spec = nullable(spec, "quiet", before.quiet(), Boolean.class);
        spec = nullable(spec, "turbo", before.turbo(), Boolean.class);
        spec = nullable(spec, "sleep", before.sleep(), Boolean.class);
        return spec.fetch().rowsUpdated().then();
    }

    public Mono<Void> close(final long id, final HoldOutcome outcome, final LocalDateTime at) {
        return databaseClient.sql(CLOSE)
            .bind("id", id)
            .bind("outcome", outcome.getValue())
            .bind("at", at)
            .fetch().rowsUpdated().then();
    }

    //the stops that ended before a time
    public Mono<Void> deleteClosedBefore(final LocalDateTime before) {
        return databaseClient.sql(DELETE_CLOSED_BEFORE).bind("before", before).fetch().rowsUpdated().then();
    }

    private static WindowHold holdOf(final Readable row) {
        return new WindowHold(
            row.get("id", Long.class),
            row.get("unit_id", Long.class),
            row.get("window_open_since", LocalDateTime.class),
            row.get("held_since", LocalDateTime.class),
            new UnitStatus(
                true,
                text(row, MODE).flatMap(Mode::ofValue).orElse(null),
                row.get("target_temperature", Integer.class),
                null,
                text(row, "fan_speed").flatMap(FanSpeed::ofValue).orElse(null),
                row.get("swing_vertical", Integer.class),
                row.get("swing_horizontal", Integer.class),
                row.get("quiet", Boolean.class),
                row.get("turbo", Boolean.class),
                row.get("sleep", Boolean.class),
                null,
                null),
            row.get("closed_at", LocalDateTime.class),
            text(row, "outcome").flatMap(word -> Arrays.stream(HoldOutcome.values())
                .filter(outcome -> outcome.getValue().equals(word)).findFirst()).orElse(null));
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
