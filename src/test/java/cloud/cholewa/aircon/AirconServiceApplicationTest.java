package cloud.cholewa.aircon;

import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AirconServiceApplicationTest {

    @Autowired
    private DatabaseProperties databaseProperties;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    //the pool is this service's share of the 47 connections of the managed database, counted twice during a
    //rollout; a lost or mistyped key falls back to the library default without a sound
    @Test
    void should_keep_the_connection_pool_at_its_share_of_the_database_budget() {
        assertThat(databaseProperties.pool().maxSize()).isEqualTo(2);
    }

    //a context of a test must not read the registry nor bind the air conditioners of the house
    @Test
    void should_schedule_nothing_under_the_test_profile() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    //the scheduler of cholewa-commons >= 1.11.0 (HAS-300): the reading tasks run on it
    @Test
    void should_give_the_scheduled_tasks_a_pool() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(10);
    }

    //the password of the database has no default: the start is refused without it (cholewa.required-properties); the
    //service has no other secret yet
    @Test
    void should_require_the_password_of_the_database() {
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[0].property"))
            .isEqualTo("database.password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[0].variable"))
            .isEqualTo("database-password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[1].property")).isNull();
    }


    //the plugs that feed the units (their power card, later in the epic) are read through the clients of shelly-client
    @Test
    void should_have_the_clients_and_the_decoder_of_shelly_client() {
        assertThat(applicationContext.getBeanNamesForType(ShellyRpcClient.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ShellyGen1Client.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ShellyMqttDecoder.class)).hasSize(1);
    }
}
