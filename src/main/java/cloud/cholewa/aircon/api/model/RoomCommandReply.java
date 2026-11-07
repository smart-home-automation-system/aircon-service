package cloud.cholewa.aircon.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

//What a command of a room came to for one unit (HAS-382): sent - the unit took the bytes, the confirmation follows in
//the reads - or not, with the code of the refusal (INVALID_COMMAND for a unit the rules refuse it for, DEVICE_FAILED)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomCommandReply(long id, boolean sent, String code) {
}
