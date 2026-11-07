package cloud.cholewa.aircon.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

//The automatic commands (HAS-385, V6), a statement each
@Repository
@RequiredArgsConstructor
public class RuleRunRepository {

    private static final String INSERT = """
        INSERT INTO aircon_rule_run (run_at, source, unit_id, episode, command, result)
        VALUES (:at, :source, :unitId, :episode, :command, :result)
        """;

    private static final String DELETE_BEFORE = "DELETE FROM aircon_rule_run WHERE run_at < :before";

    private final DatabaseClient databaseClient;

    public Mono<Void> insert(final RuleRun run) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT)
            .bind("at", run.at())
            .bind("source", run.source().getValue())
            .bind("unitId", run.unitId())
            .bind("command", run.command())
            .bind("result", run.result().getValue());
        return (run.episode() == null ? spec.bindNull("episode", LocalDateTime.class) : spec.bind("episode", run.episode()))
            .fetch().rowsUpdated().then();
    }

    public Mono<Void> deleteBefore(final LocalDateTime before) {
        return databaseClient.sql(DELETE_BEFORE).bind("before", before).fetch().rowsUpdated().then();
    }
}
