package cloud.cholewa.aircon.power;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

//The draw of the plugs (HAS-390, V8), a statement each - PostgreSQL's window functions and ON CONFLICT
@Repository
@RequiredArgsConstructor
public class PowerRepository {

    private static final String INSERT_SAMPLE = """
        INSERT INTO aircon_power_sample (plug, sampled_at, power, voltage, current, total_wh, output)
        VALUES (:plug, :at, :power, :voltage, :current, :totalWh, :output)
        """;

    //the rises of the counter between the samples from "from" up to, not including, "to" - the rise from the last sample
    //before "from" counted with the first one; a fall (a reset of the counter) never counted. In kWh, null when no rise
    //could be counted (no two samples)
    private static final String ENERGY = """
        SELECT SUM(GREATEST(total_wh - previous, 0)) / 1000.0 AS kwh
        FROM (SELECT sampled_at, total_wh, LAG(total_wh) OVER (ORDER BY sampled_at) AS previous
              FROM aircon_power_sample
              WHERE plug = :plug AND total_wh IS NOT NULL AND sampled_at < :to
                AND sampled_at >= COALESCE((SELECT max(sampled_at) FROM aircon_power_sample
                                            WHERE plug = :plug AND total_wh IS NOT NULL AND sampled_at < :from), :from)
             ) counted
        WHERE sampled_at >= :from AND previous IS NOT NULL
        """;

    //a day closed only when something was sampled on it - a day the service did not run is not known, never 0 kWh
    private static final String CLOSE_DAY = """
        INSERT INTO aircon_power_day (plug, day, kwh)
        SELECT :plug, :day, COALESCE(energy.kwh, 0)
        FROM (%s) energy
        WHERE EXISTS (SELECT 1 FROM aircon_power_sample WHERE plug = :plug AND sampled_at >= :from AND sampled_at < :to)
        ON CONFLICT (plug, day) DO NOTHING
        """.formatted(ENERGY);

    private static final String DAYS = """
        SELECT SUM(kwh) AS kwh FROM aircon_power_day WHERE plug = :plug AND day >= :from AND day < :to
        """;

    //the buckets start on the clock of the house: the times are stored as house time, and the epoch of a timestamp
    //without a zone counts from its own midnight (the query of the histories of energy-service)
    private static final String BUCKETS = """
        SELECT TIMESTAMP 'epoch'
                   + floor(extract(epoch FROM sampled_at) / :bucketSeconds) * :bucketSeconds * INTERVAL '1 second' AS at,
               avg(power) AS average, min(power) AS lowest, max(power) AS highest
        FROM aircon_power_sample
        WHERE plug = :plug AND sampled_at >= :from AND sampled_at < :to
        GROUP BY 1
        ORDER BY 1
        """;

    private static final String INSERT_CYCLE = """
        INSERT INTO aircon_power_cycle (plug, started_at, requested_by, result)
        VALUES (:plug, :at, :requestedBy, 'running')
        RETURNING id
        """;

    private static final String FINISH_CYCLE = """
        UPDATE aircon_power_cycle SET finished_at = :at, result = :result WHERE id = :id
        """;

    private static final String LAST_CYCLE = """
        SELECT started_at, finished_at, requested_by, result FROM aircon_power_cycle
        WHERE plug = :plug ORDER BY started_at DESC, id DESC LIMIT 1
        """;

    private static final String BEFORE = "before";

    private static final String RUNNING_CYCLES = "SELECT id FROM aircon_power_cycle WHERE result = 'running'";

    private final DatabaseClient databaseClient;

    public Mono<Void> insertSample(final PlugReading reading) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT_SAMPLE)
            .bind("plug", reading.plug().getValue())
            .bind("at", reading.at())
            .bind("power", reading.power());
        spec = bindNullable(spec, "voltage", reading.voltage(), Double.class);
        spec = bindNullable(spec, "current", reading.current(), Double.class);
        spec = bindNullable(spec, "totalWh", reading.totalWh(), Double.class);
        spec = bindNullable(spec, "output", reading.output(), Boolean.class);
        return spec.fetch().rowsUpdated().then();
    }

    public Mono<Optional<Double>> energy(final PlugName plug, final LocalDateTime from, final LocalDateTime to) {
        return databaseClient.sql(ENERGY)
            .bind("plug", plug.getValue())
            .bind("from", from)
            .bind("to", to)
            .map(row -> Optional.ofNullable(row.get("kwh", Double.class)))
            .one();
    }

    public Mono<Void> closeDay(final PlugName plug, final LocalDate day) {
        return databaseClient.sql(CLOSE_DAY)
            .bind("plug", plug.getValue())
            .bind("day", day)
            .bind("from", day.atStartOfDay())
            .bind("to", day.plusDays(1).atStartOfDay())
            .fetch().rowsUpdated().then();
    }

    //the energy of the closed days from "from" up to, not including, "to"; empty when none of them is closed
    public Mono<Optional<Double>> days(final PlugName plug, final LocalDate from, final LocalDate to) {
        return databaseClient.sql(DAYS)
            .bind("plug", plug.getValue())
            .bind("from", from)
            .bind("to", to)
            .map(row -> Optional.ofNullable(row.get("kwh", Double.class)))
            .one();
    }

    public Flux<PowerBucket> buckets(final PlugName plug, final LocalDateTime from, final LocalDateTime to,
                                     final long bucketSeconds) {
        return databaseClient.sql(BUCKETS)
            .bind("plug", plug.getValue())
            .bind("from", from)
            .bind("to", to)
            .bind("bucketSeconds", bucketSeconds)
            .map(row -> new PowerBucket(row.get("at", LocalDateTime.class), row.get("average", Double.class),
                row.get("lowest", Double.class), row.get("highest", Double.class)))
            .all();
    }

    public Mono<Long> insertCycle(final PlugName plug, final LocalDateTime at, final String requestedBy) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT_CYCLE)
            .bind("plug", plug.getValue())
            .bind("at", at);
        return bindNullable(spec, "requestedBy", requestedBy, String.class)
            .map(row -> row.get("id", Long.class))
            .one();
    }

    public Mono<Void> finishCycle(final long id, final LocalDateTime at, final CycleResult result) {
        return databaseClient.sql(FINISH_CYCLE)
            .bind("id", id)
            .bind("at", at)
            .bind("result", result.getValue())
            .fetch().rowsUpdated().then();
    }

    public Mono<PowerCycleRun> lastCycle(final PlugName plug) {
        return databaseClient.sql(LAST_CYCLE)
            .bind("plug", plug.getValue())
            .map(row -> new PowerCycleRun(row.get("started_at", LocalDateTime.class),
                row.get("finished_at", LocalDateTime.class), row.get("requested_by", String.class),
                CycleResult.ofValue(row.get("result", String.class)).orElse(null)))
            .one();
    }

    public Flux<Long> runningCycles() {
        return databaseClient.sql(RUNNING_CYCLES).map(row -> row.get("id", Long.class)).all();
    }

    public Mono<Void> deleteSamplesBefore(final LocalDateTime before) {
        return databaseClient.sql("DELETE FROM aircon_power_sample WHERE sampled_at < :before")
            .bind(BEFORE, before).fetch().rowsUpdated().then();
    }

    public Mono<Void> deleteDaysBefore(final LocalDate before) {
        return databaseClient.sql("DELETE FROM aircon_power_day WHERE day < :before")
            .bind(BEFORE, before).fetch().rowsUpdated().then();
    }

    public Mono<Void> deleteCyclesBefore(final LocalDateTime before) {
        return databaseClient.sql("DELETE FROM aircon_power_cycle WHERE started_at < :before AND result <> 'running'")
            .bind(BEFORE, before).fetch().rowsUpdated().then();
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(final DatabaseClient.GenericExecuteSpec spec,
                                                                      final String name, final T value,
                                                                      final Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
