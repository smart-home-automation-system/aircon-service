package cloud.cholewa.aircon.registry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceRegistryClientTest {

    private final ListAppender<ILoggingEvent> clientLog = new ListAppender<>();
    private MockWebServer server;
    private DeviceRegistryClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new DeviceRegistryClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
        clientLog.start();
        ((Logger) LoggerFactory.getLogger(DeviceRegistryClient.class)).addAppender(clientLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DeviceRegistryClient.class)).detachAppender(clientLog);
        server.close();
    }

    @Test
    void should_read_the_switched_on_air_conditioners() throws InterruptedException {
        server.enqueue(json("""
            [{"id":140,"name":"Air conditioner","room":"office","kind":"air conditioner","enabled":true,"vendor":"gree",
              "gree":{"address":"192.0.2.59","mac":"02005e0000a1"}}]
            """));

        client.getAirConditioners().as(StepVerifier::create)
            .assertNext(devices -> assertThat(devices).singleElement().satisfies(device -> {
                assertThat(device.getId()).isEqualTo(140L);
                assertThat(device.getGree().getAddress()).isEqualTo("192.0.2.59");
                assertThat(device.getGree().getMac()).isEqualTo("02005e0000a1");
            }))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/device/registry");
        assertThat(request.getUrl().queryParameter("kind")).isEqualTo("air conditioner");
        assertThat(request.getUrl().queryParameter("enabled")).isEqualTo("true");
    }

    //an error is never an empty list - that would forget the units; what the downstream said is a WARN
    @Test
    void should_fail_when_database_service_fails() {
        server.enqueue(new MockResponse.Builder().code(500).addHeader("Content-Type", "application/json")
            .body("{\"errors\":[{\"message\":\"boom\"}]}").build());

        client.getAirConditioners().as(StepVerifier::create)
            .expectErrorMessage("database-service answered 500")
            .verify();

        assertThat(clientLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).startsWith("database-service answered 500 to the read of the air conditioners");
        });
    }

    @Test
    void should_fail_on_an_answer_without_a_body() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.getAirConditioners().as(StepVerifier::create)
            .expectErrorMessage("database-service answered without a list")
            .verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build();
    }
}
