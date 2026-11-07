package cloud.cholewa.aircon.driver;

import cloud.cholewa.aircon.driver.wire.GreeEnvelope;
import cloud.cholewa.aircon.driver.wire.GreeReply;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.unit.UnitStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

//The Gree local protocol of one unit (HAS-380, the probe of 2026-11-04): the scan with the V1 cipher, only to confirm
//that the address of the registry answers with the MAC of the registry; the bind, the status and the commands with
//the V2 cipher - the bind under the generic key, everything after it under the key of the unit from its bindok.
//
//The key of a unit lives in memory only: bound at the start or at the first request, bound again when an answer does
//not open with it (the unit was bound by somebody else, or restarted) and after rebind-after failed requests in a row.
//It is never stored, never logged, never part of a message; the scan is the one answer read without a key
@Slf4j
@Component
@RequiredArgsConstructor
public class GreeClient {

    private static final int BIND = 1;
    private static final int SEALED = 0;

    private final GreeTransport transport;
    private final JsonMapper jsonMapper;
    private final GreeProperties properties;
    private final Map<String, String> keys = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

    //the MAC the unit at the address answers the scan with
    public Mono<String> scan(final String address) {
        return transport.exchange(address, write(GreeEnvelope.scan()))
            .map(answer -> read(answer, GreeEnvelope.class, address))
            .filter(envelope -> envelope.pack() != null)
            .switchIfEmpty(Mono.error(() -> new GreeCallException("The scan answer of the unit at " + address
                + " is not the model")))
            .map(envelope -> read(GreeCipher.decryptV1(GreeCipher.V1_GENERIC_KEY, envelope.pack()), GreeReply.class,
                address))
            .filter(reply -> "dev".equals(reply.t()))
            .mapNotNull(reply -> Optional.ofNullable(reply.mac()).orElse(reply.cid()))
            .switchIfEmpty(Mono.error(() -> new GreeCallException("The scan answer of the unit at " + address
                + " is not the model")));
    }

    //the key of the unit, asked for under the generic key once the address answered with the MAC of the registry
    public Mono<Void> bind(final AirConditioner unit) {
        return scan(unit.address())
            .filter(unit.mac()::equalsIgnoreCase)
            .switchIfEmpty(Mono.error(() -> new GreeCallException("The unit at " + unit.address()
                + " answers with another MAC than " + unit.mac())))
            .flatMap(ignored -> sealed(unit, GreeCipher.V2_GENERIC_KEY, BIND,
                write(ordered("mac", unit.mac(), "t", "bind", "uid", 0))))
            .filter(reply -> "bindok".equals(reply.t()) && GreeReplies.succeeded(reply)
                && GreeReplies.isOf(reply, unit.mac()) && GreeCipher.isKey(reply.key()))
            .switchIfEmpty(Mono.error(() -> new GreeCallException("The unit " + unit.mac() + " did not bind")))
            .doOnNext(reply -> {
                keys.put(unit.mac(), reply.key());
                log.info("Air conditioner {} ({}) bound", unit.id(), unit.mac());
            })
            .then();
    }

    public boolean isBound(final AirConditioner unit) {
        return keys.containsKey(unit.mac());
    }

    public Mono<UnitStatus> status(final AirConditioner unit) {
        return withKey(unit, key -> sealed(unit, key, SEALED,
            write(ordered("cols", GreeStatusMapper.COLUMNS, "mac", unit.mac(), "t", "status")))
            .map(reply -> GreeStatusMapper.map(reply, unit.mac())));
    }

    //sets the columns to the values, in the order given; the unit answers what it took
    public Mono<Void> command(final AirConditioner unit, final Map<String, Integer> values) {
        final Map<String, Object> command = new LinkedHashMap<>();
        command.put("opt", new ArrayList<>(values.keySet()));
        command.put("p", new ArrayList<>(values.values()));
        command.put("t", "cmd");
        return withKey(unit, key -> sealed(unit, key, SEALED, write(command))
            .filter(reply -> "res".equals(reply.t()) && GreeReplies.succeeded(reply)
                && GreeReplies.isOf(reply, unit.mac()))
            .switchIfEmpty(Mono.error(() -> new GreeCallException("The unit " + unit.mac() + " refused the command "
                + List.copyOf(values.keySet()))))
            .then(Mono.just(true)))
            .then();
    }

    private <T> Mono<T> withKey(final AirConditioner unit, final Function<String, Mono<T>> request) {
        return key(unit)
            .flatMap(request)
            .onErrorResume(GreeDecryptException.class, failure -> Mono.defer(() -> {
                log.warn("The answer of air conditioner {} ({}) did not open with its key, it is bound again",
                    unit.id(), unit.mac());
                keys.remove(unit.mac());
                return key(unit).flatMap(request);
            }))
            .doOnNext(ignored -> failures.remove(unit.mac()))
            .doOnError(failure -> counted(unit));
    }

    private Mono<String> key(final AirConditioner unit) {
        return Mono.defer(() -> Mono.justOrEmpty(keys.get(unit.mac())))
            .switchIfEmpty(Mono.defer(() -> bind(unit).then(Mono.fromSupplier(() -> keys.get(unit.mac())))));
    }

    //after rebind-after failed requests in a row the key is given up - the next request binds the unit first
    private void counted(final AirConditioner unit) {
        final int failed = failures.computeIfAbsent(unit.mac(), ignored -> new AtomicInteger()).incrementAndGet();
        if (failed >= properties.rebindAfter() && keys.remove(unit.mac()) != null) {
            failures.remove(unit.mac());
            log.info("Air conditioner {} ({}) failed {} request(s) in a row, it is bound again at the next one",
                unit.id(), unit.mac(), failed);
        }
    }

    private Mono<GreeReply> sealed(final AirConditioner unit, final String key, final int i, final String json) {
        final GreeCipher.Sealed request = GreeCipher.encryptV2(key, json);
        return transport.exchange(unit.address(), write(GreeEnvelope.request(i, unit.mac(), request.pack(),
                request.tag())))
            .map(answer -> read(answer, GreeEnvelope.class, unit.address()))
            .map(envelope -> read(GreeCipher.decryptV2(key, envelope.pack(), envelope.tag()), GreeReply.class,
                unit.address()));
    }

    //the fields in the order the open-source clients send them - the units do not care, a reader of a capture does
    private static Map<String, Object> ordered(final Object... fields) {
        final Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            map.put((String) fields[index], fields[index + 1]);
        }
        return map;
    }

    private String write(final Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    private <T> T read(final String json, final Class<T> type, final String address) {
        try {
            return jsonMapper.readValue(json, type);
        } catch (JacksonException failure) {
            throw new GreeCallException("The answer of the unit at " + address + " is not JSON");
        }
    }
}
