package cloud.cholewa.aircon.automation;

import cloud.cholewa.commons.error.DownstreamErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

//The grid of the house, from energy-service over k8s DNS (HAS-385, GET /home/energy/status, energy-service >= 0.4.0) -
//only its grid read, the sources left unread. What a failing energy-service says goes to the log; no body, or a body
//without the grid, is a failed call - never a grid present
@Slf4j
@Component
public class EnergyClient {

    private final WebClient energyServiceWebClient;

    public EnergyClient(@Qualifier("energyServiceWebClient") final WebClient energyServiceWebClient) {
        this.energyServiceWebClient = energyServiceWebClient;
    }

    public Mono<GridState> grid() {
        return energyServiceWebClient
            .get()
            .uri("/home/energy/status")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("energy-service answered {} to the read of the status: {}", error.status().value(),
                        error.errors());
                    return new IllegalStateException("energy-service answered " + error.status().value());
                }))
            .bodyToMono(EnergyStatus.class)
            .mapNotNull(EnergyStatus::grid)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("energy-service answered without the grid")));
    }

    //the answer of GET /home/energy/status, only what this service reads of it
    record EnergyStatus(GridState grid) {
    }
}
