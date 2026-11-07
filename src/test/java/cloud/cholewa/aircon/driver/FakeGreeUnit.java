package cloud.cholewa.aircon.driver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

//A Gree unit on a UDP port of the loopback, speaking the protocol of the probe of 2026-11-04: the scan answered with V1
//under the generic key, the bind with V2 under the generic key, the status and the commands with V2 under its own key.
//Its MAC and key are made up (a locally administered MAC, a key no unit has) - the packets of a real unit carry its
//key and never go into a public repository. It can lose datagrams, answer with a wrong tag, bind with a new key, and
//records every request it decrypted
final class FakeGreeUnit implements AutoCloseable {

    static final String MAC = "02005e0000a1";
    static final String KEY = "Fake0Key1Of2Unit";
    static final String NEW_KEY = "Fake3Key4Of5Unit";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DatagramSocket socket;
    private final Thread loop;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger toLose = new AtomicInteger();
    private final AtomicInteger received = new AtomicInteger();
    private volatile String key = KEY;
    private volatile String bindKey = KEY;
    private volatile String scanMac = MAC;
    private volatile boolean wrongTag;
    private volatile boolean refuseCommands;
    private volatile List<Object> statusValues = List.of(1, 1, 22, 0, 2, 0, 0, 0, 0, 0, 1, 64,
        "362001065279+U-WB05RT13V1.45.bin");

    FakeGreeUnit() throws SocketException {
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        loop = new Thread(this::serve, "fake-gree-unit");
        loop.setDaemon(true);
        loop.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    void loseNext(final int datagrams) {
        toLose.set(datagrams);
    }

    void answerWithWrongTag(final boolean wrong) {
        wrongTag = wrong;
    }

    //the unit was bound by somebody else: everything sealed with the old key fails, the next bind gives the new one
    void rekey() {
        key = NEW_KEY;
        bindKey = NEW_KEY;
    }

    void scanAnswersWith(final String mac) {
        scanMac = mac;
    }

    void refuseCommands() {
        refuseCommands = true;
    }

    void reportStatus(final List<Object> values) {
        statusValues = values;
    }

    List<JsonNode> requests() {
        return requests;
    }

    int received() {
        return received.get();
    }

    @Override
    public void close() {
        socket.close();
    }

    private void serve() {
        final byte[] buffer = new byte[65_507];
        while (!socket.isClosed()) {
            try {
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                received.incrementAndGet();
                if (toLose.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                    continue;
                }
                final String answer = answer(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                final byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private String answer(final String datagram) {
        final JsonNode envelope = JSON.readTree(datagram);
        if ("scan".equals(envelope.path("t").asString())) {
            final ObjectNode dev = JSON.createObjectNode().put("t", "dev").put("cid", scanMac).put("mac", scanMac)
                .put("hid", "362001065279+U-WB05RT13V1.45.bin");
            return JSON.createObjectNode().put("t", "pack").put("i", 1).put("uid", 0).put("cid", scanMac).put("tcid", "")
                .put("pack", GreeCipher.encryptV1(GreeCipher.V1_GENERIC_KEY, dev.toString())).toString();
        }
        final boolean bind = envelope.path("i").asInt() == 1;
        final String opening = bind ? GreeCipher.V2_GENERIC_KEY : key;
        final JsonNode request;
        try {
            request = JSON.readTree(GreeCipher.decryptV2(opening, envelope.path("pack").asString(),
                envelope.path("tag").asString()));
        } catch (GreeDecryptException failure) {
            //a real unit says nothing to a request it cannot open
            return "{}";
        }
        requests.add(request);
        final ObjectNode reply = switch (request.path("t").asString()) {
            case "bind" -> JSON.createObjectNode().put("t", "bindok").put("mac", MAC).put("key", bindKey).put("r", 200);
            case "status" -> {
                final ObjectNode dat = JSON.createObjectNode().put("t", "dat").put("mac", MAC).put("r", 200);
                dat.set("cols", request.path("cols"));
                dat.set("dat", JSON.valueToTree(statusValues));
                yield dat;
            }
            case "cmd" -> {
                final ObjectNode res = JSON.createObjectNode().put("t", "res").put("mac", MAC)
                    .put("r", refuseCommands ? 400 : 200);
                res.set("opt", request.path("opt"));
                res.set("val", request.path("p"));
                yield res;
            }
            default -> JSON.createObjectNode().put("t", "unknown");
        };
        final GreeCipher.Sealed sealed = GreeCipher.encryptV2(bind ? GreeCipher.V2_GENERIC_KEY : key, reply.toString());
        final String tag = wrongTag ? GreeCipher.encryptV2(bind ? GreeCipher.V2_GENERIC_KEY : key, "{}").tag()
            : sealed.tag();
        return JSON.createObjectNode().put("t", "pack").put("i", bind ? 1 : 0).put("uid", 0).put("cid", MAC)
            .put("tcid", "app").put("pack", sealed.pack()).put("tag", tag).toString();
    }
}
