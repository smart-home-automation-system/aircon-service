package cloud.cholewa.aircon.registry;

//a read of the device registry that did not end with a list of devices; never answered - a failed refresh keeps
//the air conditioners it had, and is a WARN
public class RegistryCallException extends RuntimeException {

    public RegistryCallException(final String message) {
        super(message);
    }
}
