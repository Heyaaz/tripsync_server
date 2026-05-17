package com.tripsync

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication
@EnableJpaAuditing
@ConfigurationPropertiesScan
class TripSyncApplication

fun main(args: Array<String>) {
    runApplication<TripSyncApplication>(*args)
}
