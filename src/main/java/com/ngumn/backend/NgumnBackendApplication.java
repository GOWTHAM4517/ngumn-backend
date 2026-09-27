package com.ngumn.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class NgumnBackendApplication {

	public static void main(String[] args) {
		// Times go to the app as local date-times without a zone (Java
		// LocalDateTime), and the app reads them as the phone's local time.
		// Cloud servers run on UTC, which made every report look 5.5 hours
		// old - so the server keeps India time, like it did on the laptop.
		// Override with APP_TIMEZONE if the app is ever used elsewhere.
		String zone = System.getenv().getOrDefault("APP_TIMEZONE", "Asia/Kolkata");
		TimeZone.setDefault(TimeZone.getTimeZone(zone));
		SpringApplication.run(NgumnBackendApplication.class, args);
	}

}
