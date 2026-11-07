package cloud.cholewa.aircon.driver;

//No answer of a unit to a request sent 1 + retries times
public class GreeTimeoutException extends GreeCallException {

    public GreeTimeoutException(final String address, final int attempts) {
        super("No answer from the unit at " + address + " after " + attempts + " attempt(s)");
    }
}
