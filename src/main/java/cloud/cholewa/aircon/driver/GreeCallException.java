package cloud.cholewa.aircon.driver;

//A request to a unit that did not end with the answer of the model: no answer after every attempt, an answer that
//could not be decrypted, one that is not the model, or a unit that refused it. The message names what went wrong,
//never a key nor what a packet carried - it is logged
public class GreeCallException extends RuntimeException {

    public GreeCallException(final String message) {
        super(message);
    }
}
