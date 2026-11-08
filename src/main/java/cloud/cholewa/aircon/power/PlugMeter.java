package cloud.cholewa.aircon.power;

import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.EnergyCounter;
import cloud.cholewa.shelly.model.Pm1Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;

//A plug read and switched through shelly-client (HAS-390): Switch.GetStatus of the indoor plug, PM1.GetStatus of the
//meter of sanctum (shelly-client >= 2.7.0); an answer without the power is a failed read, never 0 W. Switch.Set of the
//indoor plug for the power cycle - the only command of the service to a plug
@Component
@RequiredArgsConstructor
public class PlugMeter {

    private final ShellyRpcClient shellyRpcClient;

    public Mono<PlugReading> read(final PlugName name, final PlugProperties.Plug plug, final LocalDateTime at) {
        return switch (plug.meter()) {
            case SWITCH -> shellyRpcClient.switchStatus(plug.address(), plug.channel())
                .flatMap(status -> withPower(name, status.getApower())
                    .map(power -> new PlugReading(name, at, power, status.getVoltage(), status.getCurrent(),
                        total(status.getAenergy()), status.getOutput())));
            case PM1 -> shellyRpcClient.pm1Status(plug.address(), plug.channel())
                .flatMap(status -> withPower(name, status.getApower())
                    .map(power -> reading(name, at, power, status)));
        };
    }

    public Mono<Void> switchTo(final PlugProperties.Plug plug, final boolean on) {
        return shellyRpcClient.switchSet(plug.address(), plug.channel(), on);
    }

    private static PlugReading reading(final PlugName name, final LocalDateTime at, final double power,
                                       final Pm1Status status) {
        return new PlugReading(name, at, power, status.getVoltage(), status.getCurrent(), total(status.getAenergy()), null);
    }

    private static Mono<Double> withPower(final PlugName name, final Double power) {
        return Mono.justOrEmpty(power)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("The plug " + name.getValue()
                + " answered without its power")));
    }

    private static Double total(final EnergyCounter counter) {
        return Optional.ofNullable(counter).map(EnergyCounter::getTotal).orElse(null);
    }
}
