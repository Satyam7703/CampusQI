package com.smartcampus.attendance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
public class SmartCampusApplication { public static void main(String[] args) { SpringApplication.run(SmartCampusApplication.class, args); } }
