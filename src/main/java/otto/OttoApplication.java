package otto;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OttoApplication {

    public static void main(String[] args) {
        SpringApplication.run(OttoApplication.class, args);
    }

    /** All time reads go through this injected clock, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
