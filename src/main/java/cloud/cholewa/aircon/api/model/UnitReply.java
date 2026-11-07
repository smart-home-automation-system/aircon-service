package cloud.cholewa.aircon.api.model;

import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.home.model.RoomName;

//One air conditioner of GET /home/aircon (HAS-380): the unit of the registry, whether it answered the live read of its
//status, and that status - null, written out, when it did not answer
public record UnitReply(long id, String name, RoomName room, boolean reachable, UnitStatus status) {
}
