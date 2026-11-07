package cloud.cholewa.aircon.driver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//The vectors were sealed by Node.js (crypto, aes-128-ecb and aes-128-gcm with the nonce and the AAD of the protocol) -
//another implementation than the JDK, so a mistake in the parameters cannot hide by being made on both sides. The
//MAC is made up; the keys are the generic keys, published by the open-source clients
class GreeCipherTest {

    private static final String SCAN = "{\"t\":\"dev\",\"cid\":\"02005e0000a1\",\"mac\":\"02005e0000a1\","
        + "\"hid\":\"362001065279+U-WB05RT13V1.45.bin\",\"ver\":\"V3.4.M\",\"mid\":\"10001\"}";
    private static final String SCAN_PACK = "LP24Ek0OaYogxs3iQLjL4LlNCm/ahwaLRNZqV1NAALI33fyJAgQFxkEhWT401jp0VAMcdnPqui4x"
        + "rrnpf/pOYZzpH/fNedww9YxErCGrmIZHCBjCmce2QiwK5KJ27Ow0PADMp3uzRFWC7i0W4ePo2RdqN2m//D4f/E6YBVWM6go=";
    private static final String BIND = "{\"mac\":\"02005e0000a1\",\"t\":\"bind\",\"uid\":0}";
    private static final String BIND_PACK = "JtoT1XUt89LjtMyHbyxFB59x6dIAzgWyIl52X7OFwurdNHgGiUNkepU=";
    private static final String BIND_TAG = "8Q4rSqMJYowA9vtJJg455Q==";

    @Test
    void should_open_a_scan_answer_with_the_generic_key_of_v1() {
        assertThat(GreeCipher.decryptV1(GreeCipher.V1_GENERIC_KEY, SCAN_PACK)).isEqualTo(SCAN);
        assertThat(GreeCipher.encryptV1(GreeCipher.V1_GENERIC_KEY, SCAN)).isEqualTo(SCAN_PACK);
    }

    @Test
    void should_seal_a_bind_with_the_generic_key_of_v2() {
        assertThat(GreeCipher.encryptV2(GreeCipher.V2_GENERIC_KEY, BIND))
            .isEqualTo(new GreeCipher.Sealed(BIND_PACK, BIND_TAG));
        assertThat(GreeCipher.decryptV2(GreeCipher.V2_GENERIC_KEY, BIND_PACK, BIND_TAG)).isEqualTo(BIND);
    }

    @Test
    void should_open_what_it_sealed_with_the_key_of_a_unit() {
        final GreeCipher.Sealed sealed = GreeCipher.encryptV2(FakeGreeUnit.KEY, "{\"t\":\"status\"}");

        assertThat(GreeCipher.decryptV2(FakeGreeUnit.KEY, sealed.pack(), sealed.tag())).isEqualTo("{\"t\":\"status\"}");
    }

    //the tag is what proves the answer came from the unit: a wrong one is a failed decrypt, never a garbled JSON
    @Test
    void should_refuse_a_wrong_tag() {
        final String otherTag = GreeCipher.encryptV2(GreeCipher.V2_GENERIC_KEY, "{}").tag();

        assertThatThrownBy(() -> GreeCipher.decryptV2(GreeCipher.V2_GENERIC_KEY, BIND_PACK, otherTag))
            .isInstanceOf(GreeDecryptException.class)
            .hasMessage("The answer of the unit could not be decrypted");
    }

    @Test
    void should_refuse_a_pack_under_another_key() {
        assertThatThrownBy(() -> GreeCipher.decryptV2(FakeGreeUnit.KEY, BIND_PACK, BIND_TAG))
            .isInstanceOf(GreeDecryptException.class);
        assertThatThrownBy(() -> GreeCipher.decryptV1(FakeGreeUnit.KEY, SCAN_PACK))
            .isInstanceOf(GreeDecryptException.class);
    }

    @Test
    void should_refuse_what_is_not_base64_or_missing() {
        assertThatThrownBy(() -> GreeCipher.decryptV2(GreeCipher.V2_GENERIC_KEY, "not base64!", BIND_TAG))
            .isInstanceOf(GreeDecryptException.class);
        assertThatThrownBy(() -> GreeCipher.decryptV2(GreeCipher.V2_GENERIC_KEY, null, null))
            .isInstanceOf(GreeDecryptException.class);
        assertThatThrownBy(() -> GreeCipher.decryptV1(GreeCipher.V1_GENERIC_KEY, null))
            .isInstanceOf(GreeDecryptException.class);
    }

    @Test
    void should_take_sixteen_ascii_characters_for_a_key() {
        assertThat(GreeCipher.isKey(FakeGreeUnit.KEY)).isTrue();
        assertThat(GreeCipher.isKey("short")).isFalse();
        assertThat(GreeCipher.isKey("sixteen-chars-źż")).isFalse();
        assertThat(GreeCipher.isKey(null)).isFalse();
    }
}
