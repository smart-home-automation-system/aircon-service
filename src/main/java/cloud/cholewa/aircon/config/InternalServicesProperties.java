package cloud.cholewa.aircon.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

//The services this one calls inside the cluster, by k8s DNS (HAS-380). database - database-service, the device
//registry the air conditioners (the kind air conditioner, HAS-378) are read from; heating - heating-service, the windows
//of the rooms (HAS-384)
@Validated
@ConfigurationProperties("internal.service")
public record InternalServicesProperties(@NotNull URI database, @NotNull URI heating) {
}
