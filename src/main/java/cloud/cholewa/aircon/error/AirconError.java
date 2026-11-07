package cloud.cholewa.aircon.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request the API refuses (HAS-380). Their names are the codes of the answer (ErrorId.codeOf) - wire
//contract for a caller that branches on them, the dashboard first of all, pinned by AirconErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AirconError implements ErrorId {

    //the air conditioners of the registry are not known yet - database-service did not answer since the start
    REGISTRY_UNAVAILABLE("Device registry unavailable");

    private final String description;
}
