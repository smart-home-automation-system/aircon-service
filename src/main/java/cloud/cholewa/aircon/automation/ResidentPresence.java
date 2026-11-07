package cloud.cholewa.aircon.automation;

import java.time.LocalDateTime;

//Where an active member of the household is now, as presence-service answers it
//(GET /home/presence/residents/presence): since - when the member came or went, lastCheckedAt - the last pass that
//confirmed it, null for a member it has never observed. A copy of water-service (HAS-320) and light-service (HAS-263) -
//the fourth, kept by the owner's choice (HAS-385)
public record ResidentPresence(String name, Boolean present, LocalDateTime since, LocalDateTime lastCheckedAt) {
}
