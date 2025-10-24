package com.intouch.cp.lb_aip_pidirect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LbAipPiDirectApplication {

    public static void main(String[] args) {
        SpringApplication.run(LbAipPiDirectApplication.class, args);
    }

}
