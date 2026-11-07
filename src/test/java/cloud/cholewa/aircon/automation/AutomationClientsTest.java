package cloud.cholewa.aircon.automation;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//The clients of presence-service and energy-service (HAS-385): what each reads, and that no list, no grid or an error
//is a failed call - never an empty house nor a grid present
class AutomationClientsTest {

    private MockWebServer server;
    private PresenceClient presence;
    private EnergyClient energy;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        final WebClient client = WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build();
        presence = new PresenceClient(client);
        energy = new EnergyClient(client);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_read_where_the_members_are() throws InterruptedException {
        server.enqueue(json("""
            [{"name":"Krzysztof","present":false,"since":"2026-11-07T18:45:00","lastCheckedAt":"2026-11-07T19:59:00"}]
            """));

        presence.residents().as(StepVerifier::create)
            .expectNext(List.of(new ResidentPresence("Krzysztof", false, LocalDateTime.of(2026, 11, 7, 18, 45),
                LocalDateTime.of(2026, 11, 7, 19, 59))))
            .verifyComplete();
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/presence/residents/presence");
    }

    @Test
    void should_fail_on_presence_service_without_a_list_or_in_error() {
        server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").build());
        server.enqueue(new MockResponse.Builder().code(503).build());

        presence.residents().as(StepVerifier::create).expectError().verify();
        presence.residents().as(StepVerifier::create).expectError().verify();
    }

    @Test
    void should_read_the_grid_and_nothing_else() throws InterruptedException {
        server.enqueue(json("""
            {"sources":[{"source":"meter","reachable":true}],"grid":{"state":"lost","since":"2026-11-07T19:58:00"}}
            """));

        energy.grid().as(StepVerifier::create)
            .expectNext(new GridState("lost", LocalDateTime.of(2026, 11, 7, 19, 58)))
            .verifyComplete();
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/energy/status");
    }

    //energy-service writes a grid not known as a null state: not known, never present
    @Test
    void should_read_a_grid_not_known() {
        server.enqueue(json("{\"grid\":{\"state\":null}}"));

        energy.grid().as(StepVerifier::create)
            .assertNext(grid -> {
                assertThat(grid.lost()).isFalse();
                assertThat(grid.present()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void should_fail_on_energy_service_without_the_grid_or_in_error() {
        server.enqueue(json("{\"sources\":[]}"));
        server.enqueue(new MockResponse.Builder().code(500).build());

        energy.grid().as(StepVerifier::create).expectError().verify();
        energy.grid().as(StepVerifier::create).expectError().verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
    }
}
