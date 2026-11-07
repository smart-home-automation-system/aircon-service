package cloud.cholewa.aircon.driver;

import cloud.cholewa.aircon.driver.wire.GreeReply;

//What every answer of a unit is checked for (HAS-380): the result code, where the unit gives one, and the MAC. An
//answer of another unit - a wrong address in the registry, a reused one - is not the answer
final class GreeReplies {

    private static final int OK = 200;

    private GreeReplies() {
    }

    static boolean succeeded(final GreeReply reply) {
        return reply.r() == null || reply.r() == OK;
    }

    //the MAC of the answer - in mac, or in cid where a firmware leaves mac out - is the MAC asked for
    static boolean isOf(final GreeReply reply, final String mac) {
        final String answered = reply.mac() != null ? reply.mac() : reply.cid();
        return mac.equalsIgnoreCase(answered);
    }
}
