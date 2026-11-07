package cloud.cholewa.aircon.driver;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

//The two ciphers of the Gree local protocol (HAS-380, verified on all five units on 2026-11-04). A unit answers the
//scan with V1 - AES-128-ECB with PKCS7 padding - but binds, reports its status and takes commands only with V2 -
//AES-128-GCM with a fixed nonce and additional data, its tag carried apart from the pack. Both generic keys are the
//published constants of the open-source clients (greeclimate and others), not secrets; the key of a unit, from its
//bindok, is. Nothing here logs: a failure is a GreeDecryptException with a fixed message
public final class GreeCipher {

    //the generic key of V1, for the scan
    static final String V1_GENERIC_KEY = "a3K8Bx%2r8Y7#xDh";
    //the generic key of V2, for the bind
    static final String V2_GENERIC_KEY = "{yxAHAY_Lm6pbC/<";

    private static final byte[] V2_NONCE = {0x54, 0x40, 0x78, 0x44, 0x49, 0x67, 0x5a, 0x51, 0x6c, 0x5e, 0x63, 0x13};
    private static final byte[] V2_AAD = "qualcomm-test".getBytes(StandardCharsets.US_ASCII);
    private static final int TAG_BYTES = 16;
    private static final int KEY_BYTES = 16;

    private GreeCipher() {
    }

    //a pack of V2 and its tag, both base64 - the fields pack and tag of the envelope
    public record Sealed(String pack, String tag) {
    }

    static String encryptV1(final String key, final String json) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, aes(key));
            return Base64.getEncoder().encodeToString(cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("AES-128-ECB is not available", failure);
        }
    }

    static String decryptV1(final String key, final String pack) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, aes(key));
            return new String(cipher.doFinal(Base64.getDecoder().decode(pack)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException failure) {
            throw new GreeDecryptException();
        }
    }

    static Sealed encryptV2(final String key, final String json) {
        try {
            final byte[] sealed = gcm(Cipher.ENCRYPT_MODE, key).doFinal(json.getBytes(StandardCharsets.UTF_8));
            final int split = sealed.length - TAG_BYTES;
            return new Sealed(Base64.getEncoder().encodeToString(Arrays.copyOfRange(sealed, 0, split)),
                Base64.getEncoder().encodeToString(Arrays.copyOfRange(sealed, split, sealed.length)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("AES-128-GCM is not available", failure);
        }
    }

    static String decryptV2(final String key, final String pack, final String tag) {
        try {
            final byte[] body = Base64.getDecoder().decode(pack);
            final byte[] mac = Base64.getDecoder().decode(tag);
            final byte[] sealed = Arrays.copyOf(body, body.length + mac.length);
            System.arraycopy(mac, 0, sealed, body.length, mac.length);
            return new String(gcm(Cipher.DECRYPT_MODE, key).doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException failure) {
            throw new GreeDecryptException();
        }
    }

    //a key of a unit is 16 ASCII characters (an AES-128 key); anything else in a bindok is not the model
    static boolean isKey(final String key) {
        return key != null && key.length() == KEY_BYTES && StandardCharsets.US_ASCII.newEncoder().canEncode(key);
    }

    private static Cipher gcm(final int mode, final String key) throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, aes(key), new GCMParameterSpec(TAG_BYTES * 8, V2_NONCE));
        cipher.updateAAD(V2_AAD);
        return cipher;
    }

    private static SecretKeySpec aes(final String key) {
        return new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES");
    }
}
