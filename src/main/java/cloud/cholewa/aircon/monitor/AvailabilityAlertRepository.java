package cloud.cholewa.aircon.monitor;

import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Alert;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

//The open alerts of the availability monitor (HAS-383, V4), a statement each
@Repository
@RequiredArgsConstructor
public class AvailabilityAlertRepository {

    private static final String UNIT_ID = "unitId";

    private final DatabaseClient databaseClient;

    public Flux<Alert> findAll() {
        return databaseClient.sql("SELECT unit_id, name, room, since, last_alert_at FROM aircon_availability_alert")
            .map(row -> new Alert(
                row.get("unit_id", Long.class),
                row.get("name", String.class),
                row.get("room", String.class),
                row.get("since", LocalDateTime.class),
                row.get("last_alert_at", LocalDateTime.class)))
            .all();
    }

    public Mono<Void> insert(final Alert alert) {
        return databaseClient.sql("""
                INSERT INTO aircon_availability_alert (unit_id, name, room, since, last_alert_at)
                VALUES (:unitId, :name, :room, :since, :lastAlertAt)
                ON CONFLICT (unit_id) DO NOTHING
                """)
            .bind(UNIT_ID, alert.id())
            .bind("name", alert.name())
            .bind("room", alert.room())
            .bind("since", alert.since())
            .bind("lastAlertAt", alert.lastAlertAt())
            .fetch().rowsUpdated().then();
    }

    public Mono<Void> reminded(final long unitId, final LocalDateTime at) {
        return databaseClient.sql("UPDATE aircon_availability_alert SET last_alert_at = :at WHERE unit_id = :unitId")
            .bind("at", at)
            .bind(UNIT_ID, unitId)
            .fetch().rowsUpdated().then();
    }

    public Mono<Void> delete(final long unitId) {
        return databaseClient.sql("DELETE FROM aircon_availability_alert WHERE unit_id = :unitId")
            .bind(UNIT_ID, unitId)
            .fetch().rowsUpdated().then();
    }
}
