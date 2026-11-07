package cloud.cholewa.aircon.driver.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

//The decrypted inner JSON of an answer of a unit (HAS-380), whichever it answers - one record, the fields of each
//kind of answer, null where an answer has none:
//- "dev" to a scan: mac (the identity of the unit), and its firmware in hid / ver
//- "bindok" to a bind: mac and key, the key of the unit for everything that follows - never logged, never stored
//- "dat" to a status: cols, the columns asked for, and dat, their values by index
//- "res" to a command: opt, the columns set, and val (p in older firmware), the values taken
//r is the result code of the unit, 200 when it did what it was asked
@JsonIgnoreProperties(ignoreUnknown = true)
public record GreeReply(
    String t,
    String mac,
    String cid,
    Integer r,
    String key,
    List<String> cols,
    List<Object> dat,
    List<String> opt,
    List<Object> val,
    List<Object> p
) {

    //a toString of its own: the generated one would print the key of the unit
    @Override
    public String toString() {
        return "GreeReply[t=" + t + ", mac=" + mac + ", r=" + r + "]";
    }
}
