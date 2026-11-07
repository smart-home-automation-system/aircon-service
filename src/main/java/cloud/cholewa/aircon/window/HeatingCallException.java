package cloud.cholewa.aircon.window;

//a read of the rooms of heating-service that did not end with a list of rooms; never answered - the windows are then
//not known, and no unit is stopped nor set back by them
public class HeatingCallException extends RuntimeException {

    public HeatingCallException(final String message) {
        super(message);
    }
}
