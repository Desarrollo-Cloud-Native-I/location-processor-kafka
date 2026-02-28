package com.dcni.ubication_processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aplicación procesador de ubicaciones de vehículos.
 * Consume ubicaciones desde Kafka, procesa las señales y calcula
 * actualizaciones de horarios.
 */
@SpringBootApplication
public class UbicationProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(UbicationProcessorApplication.class, args);
    }

}
