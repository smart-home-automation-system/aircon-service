package cloud.cholewa.aircon.config;

import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.URI;
import java.time.Duration;

//The one shape of a client of another service of the cluster (database-service since HAS-380, heating-service since
//HAS-384): built from the injected builder, so the calls are traced and logged by Logbook (cholewa-commons >= 1.10.0);
//its own pool, and timeouts that keep a silent service from holding a scheduled pass until its bound
final class InternalClients {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;

    //Without a limit an idle connection stays in the pool for ever: after a restart of the other service, or once
    //conntrack has forgotten it, the next call is written onto a dead socket (the lesson of the gateway, HAS-212)
    static final Duration MAX_IDLE_TIME = Duration.ofMinutes(2);
    static final Duration MAX_LIFE_TIME = Duration.ofMinutes(30);

    private InternalClients() {
    }

    static ConnectionProvider pool(final String name) {
        return ConnectionProvider.builder(name)
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();
    }

    static WebClient client(final WebClient.Builder webClientBuilder, final ConnectionProvider pool, final URI baseUrl) {
        final HttpClient httpClient = HttpClient.create(pool)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);

        return webClientBuilder.clone()
            .baseUrl(baseUrl.toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
