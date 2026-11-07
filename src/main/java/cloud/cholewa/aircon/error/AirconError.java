package cloud.cholewa.aircon.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request the API refuses (HAS-380, HAS-381). Their names are the codes of the answer (ErrorId.codeOf) -
//wire contract for a caller that branches on them, the dashboard first of all, pinned by AirconErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AirconError implements ErrorId {

    //the air conditioners of the registry are not known yet - database-service did not answer since the start
    REGISTRY_UNAVAILABLE("Device registry unavailable"),

    //no air conditioner of the registry has this id
    NOT_FOUND_AIRCON("Air conditioner not found"),

    //no air conditioner of the registry is in this room - or no room of the house is called so
    NOT_FOUND_ROOM("Room not found");

    private final String description;
}
