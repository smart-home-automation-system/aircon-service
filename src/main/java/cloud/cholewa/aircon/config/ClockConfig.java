package cloud.cholewa.aircon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

//The clock of the house: the zone of the JVM (TZ=Europe/Warsaw of the image), explicit. A bean, so that a test
//moves it
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.systemDefault());
    }
}
