package cloud.cholewa.aircon.api.model;

import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.home.model.RoomName;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//One air conditioner of the reads of the API (HAS-381): the unit of the registry and its state as last read. A field
//not known is left out, never a default - a unit not read yet answers its id, name and room alone; reachable is left out
//until the first read after a start; updatedAt is the time of the last read that answered (house time). Since HAS-382:
//lastCommandConfirmed - whether the read after the last command showed it (left out while it is being confirmed, and
//before any), minimumRestoredAt - when the guard last raised a cooling target to 22 C. Since HAS-383: unreachableSince -
//since when the unit does not answer (with reachable false; counted anew after a start). Since HAS-384: window - the
//window of its room and, while it holds the unit stopped, until when the unit is set back (left out when neither is known).
//Since HAS-390: compressorRunning - whether the plug that feeds the unit draws above the standby (left out while its draw
//is not known); the indoor plug feeds four units, so true there says one of them runs. Since HAS-391: filter - the hours of
//work since its cleaning, when it was cleaned and whether it is due (left out until the first count after a start)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AirconReply(
    long id,
    String name,
    RoomName room,
    Boolean power,
    Mode mode,
    Integer targetTemperature,
    Integer roomTemperature,
    FanSpeed fanSpeed,
    Integer swingVertical,
    Integer swingHorizontal,
    Boolean quiet,
    Boolean turbo,
    Boolean sleep,
    Boolean display,
    Boolean reachable,
    LocalDateTime unreachableSince,
    LocalDateTime updatedAt,
    Boolean lastCommandConfirmed,
    LocalDateTime minimumRestoredAt,
    WindowReply window,
    Boolean compressorRunning,
    FilterReply filter
) {
}
