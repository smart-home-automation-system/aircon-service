package cloud.cholewa.aircon.power;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

//The two plugs that feed the units (HAS-390), the words of the API and of the plug columns, pinned by their CHECKs: the
//Plus Plug S of the four indoor units together (the emergency switch of the owner) and the PM Mini Gen3 of sanctum - a
//meter without a relay
@Getter
@RequiredArgsConstructor
public enum PlugName {
    INDOOR("indoor"),
    SANCTUM("sanctum");

    private final String value;

    //the plug of a word of a request, or nothing - the word is never echoed
    public static Optional<PlugName> ofValue(final String value) {
        return Arrays.stream(values()).filter(plug -> plug.value.equals(value)).findFirst();
    }
}
