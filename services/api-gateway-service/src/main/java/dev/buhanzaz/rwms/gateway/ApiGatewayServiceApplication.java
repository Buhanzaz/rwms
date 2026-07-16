package dev.buhanzaz.rwms.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ApiGatewayServiceApplication.class, args);
  }
}
