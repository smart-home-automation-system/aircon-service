package cloud.cholewa.aircon.driver.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

//The outer JSON of every datagram of the Gree protocol (HAS-380): t is "scan" for a discovery and "pack" for
//everything else; pack is the encrypted inner JSON, tag its GCM tag (V2 only); tcid the MAC of the unit a request
//is for, cid who sends it ("app" for a request, the MAC of the unit in its answer); i is 1 for a bind (the generic
//key) and 0 for anything sealed with the key of the unit
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GreeEnvelope(String t, Integer i, Integer uid, String cid, String tcid, String pack, String tag) {

    public static GreeEnvelope scan() {
        return new GreeEnvelope("scan", null, null, null, null, null, null);
    }

    public static GreeEnvelope request(final int i, final String mac, final String pack, final String tag) {
        return new GreeEnvelope("pack", i, 0, "app", mac, pack, tag);
    }
}
