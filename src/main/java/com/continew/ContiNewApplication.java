package com.continew;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ContiNewApplication {

	public static void main(String[] args) {
		SpringApplication.run(ContiNewApplication.class, args);
	}
}
