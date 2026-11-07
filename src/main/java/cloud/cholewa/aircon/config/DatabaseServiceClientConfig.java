package cloud.cholewa.aircon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

//The client of database-service (the device registry, HAS-380), in the shape of InternalClients - the shape of the
//client of appliance-service
@Configuration
public class DatabaseServiceClientConfig {

    //Spring infers only close() and shutdown() as destroy methods, so the pool is named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider databaseServiceConnectionProvider() {
        return InternalClients.pool("database-service");
    }

    @Bean
    WebClient databaseServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider databaseServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return InternalClients.client(webClientBuilder, databaseServiceConnectionProvider, services.database());
    }
}
