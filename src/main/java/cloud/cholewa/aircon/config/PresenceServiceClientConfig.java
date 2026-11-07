package cloud.cholewa.aircon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

//The client of presence-service (who is at home, HAS-385), in the shape of InternalClients
@Configuration
public class PresenceServiceClientConfig {

    //Spring infers only close() and shutdown() as destroy methods, so the pool is named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider presenceServiceConnectionProvider() {
        return InternalClients.pool("presence-service");
    }

    @Bean
    WebClient presenceServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider presenceServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return InternalClients.client(webClientBuilder, presenceServiceConnectionProvider, services.presence());
    }
}
