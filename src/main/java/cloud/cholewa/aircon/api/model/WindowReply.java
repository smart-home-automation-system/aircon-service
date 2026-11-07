package cloud.cholewa.aircon.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//The window of the room of an air conditioner (HAS-384): state (open, closed, no sensor, unknown), openSince and hold
//as heating-service last gave them - left out while heating-service does not answer; restoreUntil while the window holds
//the unit stopped - until when the closed window sets it back as it was
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WindowReply(String state, LocalDateTime openSince, Boolean hold, LocalDateTime restoreUntil) {
}
