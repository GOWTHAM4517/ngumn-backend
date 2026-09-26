package com.ngumn.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NgumnBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(NgumnBackendApplication.class, args);
	}

}
