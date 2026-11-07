package cloud.cholewa.aircon.registry;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;

import java.util.Optional;

//An air conditioner as the service reads it from the registry (HAS-378): its id, name and room, and what reaches the
//unit on the home network - its address, reserved in DHCP, and its MAC as the unit gives it (12 lower-case hex
//digits). The MAC is the identity of the unit in the protocol: every request names it, every answer carries it
public record AirConditioner(long id, String name, RoomName room, String address, String mac) {

    //the unit of a device of the registry, or nothing for a device without an id or without a whole gree binding - the
    //registry never stores one, so it is a registry the service does not understand
    static Optional<AirConditioner> of(final SmartDevice device) {
        return Optional.ofNullable(device.getGree())
            .filter(binding -> binding.getAddress() != null && binding.getMac() != null)
            .flatMap(binding -> Optional.ofNullable(device.getId()).map(id -> new AirConditioner(id, device.getName(),
                device.getRoom(), binding.getAddress(), binding.getMac())));
    }
}
