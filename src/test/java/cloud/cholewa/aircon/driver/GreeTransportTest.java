package cloud.cholewa.aircon.driver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.SocketException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GreeTransportTest {

    private FakeGreeUnit unit;
    private GreeTransport transport;

    @BeforeEach
    void setUp() throws SocketException {
        unit = new FakeGreeUnit();
        transport = new GreeTransport(new GreeProperties(unit.port(), Duration.ofMillis(300), 2, 3));
    }

    @AfterEach
    void tearDown() {
        unit.close();
    }

    @Test
    void should_answer_a_datagram_with_the_answer_of_the_unit() {
        transport.exchange("127.0.0.1", "{\"t\":\"scan\"}").as(StepVerifier::create)
            .assertNext(answer -> assertThat(answer).contains("\"t\":\"pack\"").contains("\"cid\":\"02005e0000a1\""))
            .verifyComplete();
        assertThat(unit.received()).isEqualTo(1);
    }

    //Wi-Fi loses a datagram now and then (the probe of 2026-11-04): the request is sent again
    @Test
    void should_send_again_what_was_not_answered() {
        unit.loseNext(2);

        transport.exchange("127.0.0.1", "{\"t\":\"scan\"}").as(StepVerifier::create)
            .assertNext(answer -> assertThat(answer).contains("\"t\":\"pack\""))
            .verifyComplete();
        assertThat(unit.received()).isEqualTo(3);
    }

    @Test
    void should_give_up_after_every_attempt() {
        unit.loseNext(10);

        transport.exchange("127.0.0.1", "{\"t\":\"scan\"}").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(GreeTimeoutException.class)
                .hasMessage("No answer from the unit at 127.0.0.1 after 3 attempt(s)"))
            .verify(Duration.ofSeconds(5));
        assertThat(unit.received()).isEqualTo(3);
    }

    //an address that does not resolve is a failed call naming the kind of the failure alone
    @Test
    void should_fail_on_an_address_that_is_not_one() {
        transport.exchange("not an address", "{\"t\":\"scan\"}").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isExactlyInstanceOf(GreeCallException.class)
                .hasMessage("The request to the unit at not an address failed: UnknownHostException"))
            .verify(Duration.ofSeconds(5));
    }
}
