package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.automation.AutomationEvaluator.Episode;
import cloud.cholewa.aircon.state.ChangeSource;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;

//The episode the empty house and a lost grid acted on last (HAS-385, V6), one row a source. ON CONFLICT is
//PostgreSQL's - tested on a PostgreSQL of Testcontainers
@Repository
@RequiredArgsConstructor
public class EpisodeRepository {

    private static final String SAVE = """
        INSERT INTO aircon_automation_episode (source, episode, acted_at) VALUES (:source, :episode, :at)
        ON CONFLICT (source) DO UPDATE SET episode = EXCLUDED.episode, acted_at = EXCLUDED.acted_at
        """;

    private static final String ALL = "SELECT source, episode, acted_at FROM aircon_automation_episode";

    private final DatabaseClient databaseClient;

    public Mono<Void> save(final ChangeSource source, final Episode episode) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(SAVE)
            .bind("source", source.getValue())
            .bind("at", episode.at());
        return (episode.key() == null ? spec.bindNull("episode", LocalDateTime.class) : spec.bind("episode", episode.key()))
            .fetch().rowsUpdated().then();
    }

    //the episode of every source that ever acted; a word this version does not know is left out
    public Flux<Acted> all() {
        return databaseClient.sql(ALL)
            .map(row -> new Acted(
                sourceOf(row.get("source", String.class)),
                new Episode(row.get("episode", LocalDateTime.class), row.get("acted_at", LocalDateTime.class))))
            .all()
            .filter(acted -> acted.source() != null);
    }

    private static ChangeSource sourceOf(final String word) {
        return Arrays.stream(ChangeSource.values()).filter(source -> source.getValue().equals(word)).findFirst()
            .orElse(null);
    }

    public record Acted(ChangeSource source, Episode episode) {
    }
}
