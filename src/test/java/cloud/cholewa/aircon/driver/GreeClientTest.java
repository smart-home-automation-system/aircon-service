package cloud.cholewa.aircon.driver;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GreeClientTest {

    private static final AirConditioner UNIT = new AirConditioner(140, "Air conditioner", RoomName.OFFICE, "127.0.0.1",
        FakeGreeUnit.MAC);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private FakeGreeUnit unit;
    private GreeClient client;

    @BeforeEach
    void setUp() throws SocketException {
        unit = new FakeGreeUnit();
        final GreeProperties properties = new GreeProperties(unit.port(), Duration.ofMillis(300), 2, 3);
        client = new GreeClient(new GreeTransport(properties), JsonMapper.builder().build(), properties);
        logs.start();
        ((Logger) LoggerFactory.getLogger("cloud.cholewa.aircon")).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("cloud.cholewa.aircon")).detachAppender(logs);
        unit.close();
    }

    @Test
    void should_read_the_mac_of_the_unit_from_its_scan() {
        client.scan("127.0.0.1").as(StepVerifier::create)
            .expectNext(FakeGreeUnit.MAC)
            .verifyComplete();
    }

    //the scan confirms the address, the bind under the generic key of V2 gives the key of the unit
    @Test
    void should_bind_a_unit() {
        client.bind(UNIT).as(StepVerifier::create).verifyComplete();

        assertThat(client.isBound(UNIT)).isTrue();
        assertThat(unit.requests()).singleElement().satisfies(bind -> {
            assertThat(bind.path("t").asString()).isEqualTo("bind");
            assertThat(bind.path("mac").asString()).isEqualTo(FakeGreeUnit.MAC);
        });
    }

    //an address of the registry that answers with another MAC is another unit - never bound, never commanded
    @Test
    void should_not_bind_a_unit_that_answers_with_another_mac() {
        unit.scanAnswersWith("02005e0000ff");

        client.bind(UNIT).as(StepVerifier::create)
            .expectErrorMessage("The unit at 127.0.0.1 answers with another MAC than 02005e0000a1")
            .verify();

        assertThat(client.isBound(UNIT)).isFalse();
        assertThat(unit.requests()).isEmpty();
    }

    //the status is read with the key of the unit, bound first when there is none
    @Test
    void should_read_the_status_of_a_unit() {
        client.status(UNIT).as(StepVerifier::create)
            .expectNext(new UnitStatus(true, Mode.COOL, 22, 24, FanSpeed.MEDIUM_LOW, 0, 0, false, false, false, true,
                "362001065279+U-WB05RT13V1.45.bin"))
            .verifyComplete();

        assertThat(unit.requests()).extracting(request -> request.path("t").asString())
            .containsExactly("bind", "status");
        assertThat(unit.requests().get(1).path("cols").valueStream().map(JsonNode::asString).toList())
            .isEqualTo(GreeStatusMapper.COLUMNS);
    }

    @Test
    void should_bind_once_for_many_requests() {
        client.status(UNIT).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        client.status(UNIT).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        assertThat(unit.requests()).extracting(request -> request.path("t").asString())
            .containsExactly("bind", "status", "status");
    }

    //a datagram lost on the way is sent again: the status still comes
    @Test
    void should_read_the_status_through_a_lost_datagram() {
        client.bind(UNIT).as(StepVerifier::create).verifyComplete();
        unit.loseNext(1);

        client.status(UNIT).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    //bound by somebody else (or restarted), the unit no longer opens what is sealed with the old key: it is bound
    //again and the request repeated once
    @Test
    void should_bind_again_when_the_key_no_longer_opens() {
        client.status(UNIT).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        unit.rekey();

        client.status(UNIT).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        assertThat(unit.requests()).extracting(request -> request.path("t").asString())
            .containsExactly("bind", "status", "bind", "status");
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
            .isEqualTo("The answer of air conditioner 140 (02005e0000a1) did not open with its key, it is bound again"));
    }

    //an answer whose tag is not the one of its pack did not come from the unit
    @Test
    void should_fail_on_a_wrong_tag() {
        client.bind(UNIT).as(StepVerifier::create).verifyComplete();
        unit.answerWithWrongTag(true);

        client.status(UNIT).as(StepVerifier::create)
            .expectErrorMessage("The answer of the unit could not be decrypted")
            .verify();
    }

    @Test
    void should_fail_on_a_status_that_is_not_the_model() {
        unit.reportStatus(List.of(1, 7, 22));

        client.status(UNIT).as(StepVerifier::create)
            .expectErrorMessage("The status of the unit 02005e0000a1 is not the model")
            .verify();
    }

    //after rebind-after failed requests in a row the key is given up: the next request binds first
    @Test
    void should_bind_again_after_failed_requests_in_a_row() {
        client.bind(UNIT).as(StepVerifier::create).verifyComplete();
        unit.loseNext(9);
        for (int failed = 0; failed < 3; failed++) {
            client.status(UNIT).as(StepVerifier::create).expectError(GreeTimeoutException.class).verify();
        }

        assertThat(client.isBound(UNIT)).isFalse();
        client.status(UNIT).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        assertThat(unit.requests()).extracting(request -> request.path("t").asString())
            .containsExactly("bind", "bind", "status");
    }

    @Test
    void should_send_a_command_with_its_columns_in_order() {
        final Map<String, Integer> values = new LinkedHashMap<>();
        values.put("Pow", 1);
        values.put("SetTem", 23);

        client.command(UNIT, values).as(StepVerifier::create).verifyComplete();

        final JsonNode command = unit.requests().get(1);
        assertThat(command.path("t").asString()).isEqualTo("cmd");
        assertThat(command.path("opt").valueStream().map(JsonNode::asString).toList())
            .containsExactly("Pow", "SetTem");
        assertThat(command.path("p").valueStream().map(JsonNode::asInt).toList()).containsExactly(1, 23);
    }

    @Test
    void should_fail_on_a_refused_command() {
        unit.refuseCommands();

        client.command(UNIT, Map.of("Pow", 0)).as(StepVerifier::create)
            .expectErrorMessage("The unit 02005e0000a1 refused the command [Pow]")
            .verify();
    }

    //the key of the unit and the generic keys never reach a log line (DoD of HAS-380)
    @Test
    void should_log_no_key() {
        client.status(UNIT).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        unit.rekey();
        client.status(UNIT).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        unit.answerWithWrongTag(true);
        client.status(UNIT).as(StepVerifier::create).expectError().verify();

        assertThat(logs.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
            .doesNotContain(FakeGreeUnit.KEY, FakeGreeUnit.NEW_KEY, GreeCipher.V1_GENERIC_KEY,
                GreeCipher.V2_GENERIC_KEY));
    }
}
