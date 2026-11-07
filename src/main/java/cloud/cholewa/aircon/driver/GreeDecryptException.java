package cloud.cholewa.aircon.driver;

//An answer of a unit its key does not open (a wrong tag, a pack that is not base64): the unit has a new key - it was
//bound by somebody else, or restarted - so the client binds it again
public class GreeDecryptException extends GreeCallException {

    public GreeDecryptException() {
        super("The answer of the unit could not be decrypted");
    }
}
