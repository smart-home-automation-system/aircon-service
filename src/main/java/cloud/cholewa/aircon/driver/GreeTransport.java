package cloud.cholewa.aircon.driver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

//One datagram to a unit and its answer (HAS-380): unicast UDP to the address of the registry - a broadcast from the
//cluster does not reach the VLAN of the units. A unit takes one request at a time, so the requests of one address
//wait for each other; the answer that did not come within the timeout is asked again, up to retries times.
//
//The socket API of the JDK blocks; the exchange runs on boundedElastic, which is what it is for - there is no
//blocking call anywhere on an event loop. A new socket for every attempt: its source port is new too, so a late
//answer to an attempt given up on reaches a closed socket and never passes for the answer of the next request.
//Nothing here logs: a packet carries the key of a unit in its bindok
@Component
@RequiredArgsConstructor
public class GreeTransport {

    private static final int MAX_DATAGRAM = 65_507;

    private final GreeProperties properties;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public Mono<String> exchange(final String address, final String request) {
        final int attempts = properties.retries() + 1;
        return Mono.fromCallable(() -> attempt(address, request))
            .subscribeOn(Schedulers.boundedElastic())
            .retryWhen(Retry.max(properties.retries())
                .filter(SocketTimeoutException.class::isInstance)
                .onRetryExhaustedThrow((spec, signal) -> new GreeTimeoutException(address, attempts)))
            .onErrorMap(failure -> !(failure instanceof GreeCallException),
                failure -> new GreeCallException("The request to the unit at " + address + " failed: "
                    + kind(failure)));
    }

    //the kind of the failure of the socket (PortUnreachableException, UnknownHostException), not its wrapper
    private static String kind(final Throwable failure) {
        final Throwable cause = failure instanceof UncheckedIOException && failure.getCause() != null
            ? failure.getCause() : failure;
        return cause.getClass().getSimpleName();
    }

    private String attempt(final String address, final String request) throws IOException {
        final ReentrantLock lock = locks.computeIfAbsent(address, ignored -> new ReentrantLock(true));
        lock.lock();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(Math.toIntExact(properties.timeout().toMillis()));
            //connected: the socket takes datagrams from the address of the unit alone
            socket.connect(InetAddress.getByName(address), properties.port());
            final byte[] payload = request.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(payload, payload.length));
            final DatagramPacket answer = new DatagramPacket(new byte[MAX_DATAGRAM], MAX_DATAGRAM);
            socket.receive(answer);
            return new String(answer.getData(), answer.getOffset(), answer.getLength(), StandardCharsets.UTF_8);
        } catch (SocketTimeoutException timeout) {
            throw timeout;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } finally {
            lock.unlock();
        }
    }
}
