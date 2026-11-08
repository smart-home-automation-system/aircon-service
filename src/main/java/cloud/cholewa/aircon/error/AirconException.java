package cloud.cholewa.aircon.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

//A request the API refuses (HAS-380): the status says whose problem it is, the error names the cause, the message is
//answered as details
@Getter
public class AirconException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final AirconError error;

    public AirconException(final HttpStatus httpStatus, final AirconError error, final String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public static AirconException registryUnavailable() {
        return new AirconException(HttpStatus.SERVICE_UNAVAILABLE, AirconError.REGISTRY_UNAVAILABLE,
            "The air conditioners of the device registry are not known yet");
    }

    public static AirconException airconNotFound(final long id) {
        return new AirconException(HttpStatus.NOT_FOUND, AirconError.NOT_FOUND_AIRCON,
            "No air conditioner " + id + " is registered");
    }

    public static AirconException invalidCommand(final String reason) {
        return new AirconException(HttpStatus.BAD_REQUEST, AirconError.INVALID_COMMAND, reason);
    }

    public static AirconException deviceFailed(final String message) {
        return new AirconException(HttpStatus.BAD_GATEWAY, AirconError.DEVICE_FAILED, message);
    }

    public static AirconException invalidRule(final String reason) {
        return new AirconException(HttpStatus.BAD_REQUEST, AirconError.INVALID_RULE, reason);
    }

    public static AirconException ruleNotFound(final long id) {
        return new AirconException(HttpStatus.NOT_FOUND, AirconError.NOT_FOUND_RULE, "Air conditioner " + id + " has no rule");
    }

    public static AirconException invalidRange(final String reason) {
        return new AirconException(HttpStatus.BAD_REQUEST, AirconError.INVALID_RANGE, reason);
    }

    //the plug is never echoed: it is a text of the request
    public static AirconException plugNotFound() {
        return new AirconException(HttpStatus.NOT_FOUND, AirconError.NOT_FOUND_PLUG, "No plug is called so");
    }

    public static AirconException airconRunning(final String reason) {
        return new AirconException(HttpStatus.CONFLICT, AirconError.AIRCON_RUNNING, reason);
    }

    public static AirconException powerCycleInProgress() {
        return new AirconException(HttpStatus.CONFLICT, AirconError.POWER_CYCLE_IN_PROGRESS, "A power cycle is running");
    }

    //the room is never echoed: it is a text of the request
    public static AirconException roomNotFound() {
        return new AirconException(HttpStatus.NOT_FOUND, AirconError.NOT_FOUND_ROOM,
            "No air conditioner is registered in this room");
    }
}
