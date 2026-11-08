package cloud.cholewa.aircon.filter;

import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;

//The filters and the run hours of the units (HAS-391, V9 and the history of V2), a statement each - PostgreSQL's LEAD
//and ON CONFLICT
@Repository
@RequiredArgsConstructor
public class FilterRepository {

    //the time a unit was on from "since" up to "now", in hours: every change of the history lasts until the next one (the
    //last until now), the one before "since" included from "since" on; null "since" counts the whole history
    private static final String HOURS = """
        SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (LEAST(COALESCE(next_at, :now), :now)
                                               - GREATEST(changed_at, COALESCE(:since, changed_at))))), 0)::double precision
                   / 3600.0 AS hours
        FROM (SELECT changed_at, power, LEAD(changed_at) OVER (ORDER BY changed_at, id) AS next_at
              FROM aircon_state_change
              WHERE unit_id = :unitId) history
        WHERE power AND changed_at < :now AND COALESCE(next_at, :now) > COALESCE(:since, changed_at)
        """;

    private static final String FILTERS = "SELECT unit_id, cleaned_at, reminded_hours FROM aircon_filter";

    private static final String CLEANED = """
        INSERT INTO aircon_filter (unit_id, cleaned_at, reminded_hours) VALUES (:unitId, :at, NULL)
        ON CONFLICT (unit_id) DO UPDATE SET cleaned_at = EXCLUDED.cleaned_at, reminded_hours = NULL
        """;

    private static final String REMINDED = """
        INSERT INTO aircon_filter (unit_id, cleaned_at, reminded_hours) VALUES (:unitId, NULL, :hours)
        ON CONFLICT (unit_id) DO UPDATE SET reminded_hours = EXCLUDED.reminded_hours
        """;

    private static final String RUNS = """
        SELECT changed_at, power, mode, target_temperature, fan_speed, source
        FROM aircon_state_change
        WHERE unit_id = :unitId AND changed_at >= :from AND changed_at < :to
        ORDER BY changed_at, id
        """;

    private static final String UNIT_ID = "unitId";

    private final DatabaseClient databaseClient;

    public Mono<Double> hours(final long unitId, final LocalDateTime since, final LocalDateTime now) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(HOURS)
            .bind(UNIT_ID, unitId)
            .bind("now", now);
        return (since == null ? spec.bindNull("since", LocalDateTime.class) : spec.bind("since", since))
            .map(row -> row.get("hours", Double.class))
            .one();
    }

    public Flux<FilterRecord> filters() {
        return databaseClient.sql(FILTERS)
            .map(row -> new FilterRecord(row.get("unit_id", Long.class), row.get("cleaned_at", LocalDateTime.class),
                row.get("reminded_hours", Double.class)))
            .all();
    }

    public Mono<Void> cleaned(final long unitId, final LocalDateTime at) {
        return databaseClient.sql(CLEANED).bind(UNIT_ID, unitId).bind("at", at).fetch().rowsUpdated().then();
    }

    public Mono<Void> reminded(final long unitId, final double hours) {
        return databaseClient.sql(REMINDED).bind(UNIT_ID, unitId).bind("hours", hours).fetch().rowsUpdated().then();
    }

    //the changes of a unit from "from" up to, not including, "to", oldest first; a word this version does not know is
    //left out of its field
    public Flux<UnitRun> runs(final long unitId, final LocalDateTime from, final LocalDateTime to) {
        return databaseClient.sql(RUNS)
            .bind(UNIT_ID, unitId)
            .bind("from", from)
            .bind("to", to)
            .map(row -> new UnitRun(
                row.get("changed_at", LocalDateTime.class),
                row.get("power", Boolean.class),
                text(row, "mode").flatMap(Mode::ofValue).orElse(null),
                row.get("target_temperature", Integer.class),
                text(row, "fan_speed").flatMap(FanSpeed::ofValue).orElse(null),
                text(row, "source").flatMap(ChangeSource::ofValue).orElse(null)))
            .all();
    }

    private static Optional<String> text(final Readable row, final String column) {
        return Optional.ofNullable(row.get(column, String.class));
    }
}
