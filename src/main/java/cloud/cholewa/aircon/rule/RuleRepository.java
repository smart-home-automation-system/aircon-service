package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//The rules of the units (HAS-386, V7), a statement each. ON CONFLICT is PostgreSQL's - tested on a PostgreSQL of
//Testcontainers
@Repository
@RequiredArgsConstructor
public class RuleRepository {

    private static final String COLUMNS = "unit_id, keep_below_enabled, threshold, hysteresis, target, heat_by_schedule, "
        + "updated_at";

    private static final String SAVE = """
        INSERT INTO aircon_rule (unit_id, keep_below_enabled, threshold, hysteresis, target, heat_by_schedule, updated_at)
        VALUES (:unitId, :enabled, :threshold, :hysteresis, :target, :heatBySchedule, :at)
        ON CONFLICT (unit_id) DO UPDATE SET
            keep_below_enabled = EXCLUDED.keep_below_enabled,
            threshold = EXCLUDED.threshold,
            hysteresis = EXCLUDED.hysteresis,
            target = EXCLUDED.target,
            heat_by_schedule = EXCLUDED.heat_by_schedule,
            updated_at = EXCLUDED.updated_at
        """;

    private static final String UNIT_ID = "unitId";

    private final DatabaseClient databaseClient;

    public Flux<AirconRule> findAll() {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM aircon_rule ORDER BY unit_id")
            .map(RuleRepository::ruleOf)
            .all();
    }

    public Mono<AirconRule> find(final long unitId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM aircon_rule WHERE unit_id = :unitId")
            .bind(UNIT_ID, unitId)
            .map(RuleRepository::ruleOf)
            .one();
    }

    public Mono<Void> save(final AirconRule rule) {
        return databaseClient.sql(SAVE)
            .bind(UNIT_ID, rule.unitId())
            .bind("enabled", rule.keepBelow().enabled())
            .bind("threshold", rule.keepBelow().threshold())
            .bind("hysteresis", rule.keepBelow().hysteresis())
            .bind("target", rule.keepBelow().target())
            .bind("heatBySchedule", rule.heatBySchedule().enabled())
            .bind("at", rule.updatedAt())
            .fetch().rowsUpdated().then();
    }

    //true when there was a rule to delete
    public Mono<Boolean> delete(final long unitId) {
        return databaseClient.sql("DELETE FROM aircon_rule WHERE unit_id = :unitId")
            .bind(UNIT_ID, unitId)
            .fetch().rowsUpdated()
            .map(deleted -> deleted > 0);
    }

    private static AirconRule ruleOf(final Readable row) {
        return new AirconRule(
            row.get("unit_id", Long.class),
            new AirconRule.KeepBelow(
                Boolean.TRUE.equals(row.get("keep_below_enabled", Boolean.class)),
                row.get("threshold", BigDecimal.class),
                row.get("hysteresis", BigDecimal.class),
                row.get("target", Integer.class)),
            new AirconRule.HeatBySchedule(Boolean.TRUE.equals(row.get("heat_by_schedule", Boolean.class))),
            row.get("updated_at", LocalDateTime.class));
    }
}
