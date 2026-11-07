package cloud.cholewa.aircon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

//The client of energy-service (the grid, HAS-385), in the shape of InternalClients
@Configuration
public class EnergyServiceClientConfig {

    //Spring infers only close() and shutdown() as destroy methods, so the pool is named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider energyServiceConnectionProvider() {
        return InternalClients.pool("energy-service");
    }

    @Bean
    WebClient energyServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider energyServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return InternalClients.client(webClientBuilder, energyServiceConnectionProvider, services.energy());
    }
}
