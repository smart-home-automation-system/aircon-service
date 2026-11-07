package cloud.cholewa.aircon.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request the API refuses (HAS-380, HAS-381, HAS-382, HAS-386). Their names are the codes of the answer (ErrorId.codeOf) -
//wire contract for a caller that branches on them, the dashboard first of all, pinned by AirconErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AirconError implements ErrorId {

    //the air conditioners of the registry are not known yet - database-service did not answer since the start
    REGISTRY_UNAVAILABLE("Device registry unavailable"),

    //no air conditioner of the registry has this id
    NOT_FOUND_AIRCON("Air conditioner not found"),

    //no air conditioner of the registry is in this room - or no room of the house is called so
    NOT_FOUND_ROOM("Room not found"),

    //a command the rules refuse (CommandRules: 22 C at least in cooling, 16-30 C, turbo and quiet not in dry or fan),
    //an empty one, or one of an unknown source - the details say which
    INVALID_COMMAND("Invalid command"),

    //the unit did not answer a command after every attempt - it may have taken it all the same
    DEVICE_FAILED("Device failed"),

    //a rule the rules of a rule refuse (RuleRules: 20-30 C by 0.5, a hysteresis of 0.5-3 by 0.5, a target of 22 C at
    //least and not above the threshold) - the details say which
    INVALID_RULE("Invalid rule"),

    //the unit has no rule
    NOT_FOUND_RULE("Rule not found");

    private final String description;
}
