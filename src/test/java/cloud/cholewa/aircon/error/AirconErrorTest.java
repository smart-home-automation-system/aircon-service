package cloud.cholewa.aircon.error;

import cloud.cholewa.commons.error.model.ErrorId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//the names are the codes of the answers - wire contract once the dashboard branches on them (HAS-380, HAS-381, HAS-382)
class AirconErrorTest {

    @Test
    void should_keep_the_codes_of_the_refused_requests() {
        assertThat(Arrays.stream(AirconError.values()).map(ErrorId::codeOf)).containsExactly("REGISTRY_UNAVAILABLE", "NOT_FOUND_AIRCON",
            "NOT_FOUND_ROOM", "INVALID_COMMAND", "DEVICE_FAILED");
    }
}
