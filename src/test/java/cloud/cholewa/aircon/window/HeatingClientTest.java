package cloud.cholewa.aircon.window;

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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HeatingClientTest {

    private final ListAppender<ILoggingEvent> clientLog = new ListAppender<>();
    private MockWebServer server;
    private HeatingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new HeatingClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
        clientLog.start();
        ((Logger) LoggerFactory.getLogger(HeatingClient.class)).addAppender(clientLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(HeatingClient.class)).detachAppender(clientLog);
        server.close();
    }

    //the rooms with their windows, every other field of heating-service left unread; a room this SDK may not know is text
    @Test
    void should_read_the_windows_of_the_rooms() throws InterruptedException {
        server.enqueue(json("""
            [{"name":"office","mode":"heating","window":{"state":"open","openSince":"2026-11-07T18:26:00","hold":true},
              "heaters":[{"type":"radiator","working":false}]},
             {"name":"sanctum","window":{"state":"no sensor","hold":false}},
             {"name":"winter garden","window":{"state":"closed","hold":false}}]
            """));

        client.getRooms().as(StepVerifier::create)
            .assertNext(rooms -> assertThat(rooms).containsExactly(
                new HeatingRoom("office", new RoomWindow("open", LocalDateTime.of(2026, 11, 7, 18, 26), true)),
                new HeatingRoom("sanctum", new RoomWindow("no sensor", null, false)),
                new HeatingRoom("winter garden", new RoomWindow("closed", null, false))))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/heating/rooms");
    }

    //the temperature of a room as heating-service answers it (HAS-386) - decoded by the canonical constructor
    @Test
    void should_read_the_temperature_of_a_room() {
        server.enqueue(json("""
            [{"name":"sanctum","heatingEnabled":false,"temperature":{"value":17.1,"updatedAt":"2026-11-07T18:46:49"},
              "humidity":{"value":47.2,"updatedAt":"2026-11-07T18:46:49"},"window":{"state":"no sensor","hold":false}}]
            """));

        client.getRooms().as(StepVerifier::create)
            .assertNext(rooms -> assertThat(rooms).containsExactly(new HeatingRoom("sanctum",
                new RoomWindow("no sensor", null, false),
                new HeatingRoom.Reading(17.1, LocalDateTime.of(2026, 11, 7, 18, 46, 49)))))
            .verifyComplete();
    }

    //what heating-service said goes to the log, never further
    @Test
    void should_fail_on_an_error_of_heating_service() {
        server.enqueue(new MockResponse.Builder().code(503).addHeader("Content-Type", "application/json")
            .body("{\"errors\":[{\"message\":\"Down\"}]}").build());

        client.getRooms().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(HeatingCallException.class)
                .hasMessage("heating-service answered 503"))
            .verify();
        assertThat(clientLog.list).singleElement().satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
    }

    //no body is a failed call, never a house without windows
    @Test
    void should_fail_on_an_answer_without_a_body() {
        server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").build());

        client.getRooms().as(StepVerifier::create)
            .expectError(HeatingCallException.class)
            .verify();
    }

    @Test
    void should_fail_on_an_answer_that_is_not_a_list() {
        server.enqueue(json("{}"));

        client.getRooms().as(StepVerifier::create)
            .expectError()
            .verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
    }
}
