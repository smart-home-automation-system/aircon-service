package cloud.cholewa.aircon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

//The client of heating-service (the windows of the rooms, HAS-384), in the shape of InternalClients
@Configuration
public class HeatingServiceClientConfig {

    //Spring infers only close() and shutdown() as destroy methods, so the pool is named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider heatingServiceConnectionProvider() {
        return InternalClients.pool("heating-service");
    }

    @Bean
    WebClient heatingServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider heatingServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return InternalClients.client(webClientBuilder, heatingServiceConnectionProvider, services.heating());
    }
}
