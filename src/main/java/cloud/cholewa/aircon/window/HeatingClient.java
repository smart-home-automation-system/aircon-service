package cloud.cholewa.aircon.window;

import cloud.cholewa.commons.error.DownstreamErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

//The rooms of heating-service with their windows (HAS-384, GET /home/heating/rooms of heating-service >= 1.20.0) - since
//HAS-387 with what the schedule of each heater asks for. What a
//failing heating-service says goes to the log, never further; no body at all, or one that is not a list, is a failed
//call - never a house without windows. The shape of the client of the device registry
@Slf4j
@Component
public class HeatingClient {

    private static final ParameterizedTypeReference<List<HeatingRoom>> ROOMS = new ParameterizedTypeReference<>() {
    };

    private final WebClient heatingServiceWebClient;

    public HeatingClient(@Qualifier("heatingServiceWebClient") final WebClient heatingServiceWebClient) {
        this.heatingServiceWebClient = heatingServiceWebClient;
    }

    public Mono<List<HeatingRoom>> getRooms() {
        return heatingServiceWebClient
            .get()
            .uri("/home/heating/rooms")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("heating-service answered {} to the read of the rooms: {}", error.status().value(),
                        error.errors());
                    return new HeatingCallException("heating-service answered " + error.status().value());
                }))
            .bodyToMono(ROOMS)
            .switchIfEmpty(Mono.error(() -> new HeatingCallException("heating-service answered without a list")));
    }
}
