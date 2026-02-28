package com.dcni.ubication_processor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Modelo que representa la información de horario de un vehículo.
 * Incluye datos sobre la ruta, parada, tiempo estimado de llegada y retrasos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HorarioModel {

    private String vehicleId;
    private String routeId;
    private String stopId;
    private LocalDateTime estimatedArrival;
    private LocalDateTime actualArrival;
    private String status; // "ON_TIME", "DELAYED", "EARLY", "ARRIVED"
    private Integer delayMinutes;
    private Double distanceToStop; // Distancia en kilómetros
    private LocalDateTime timestamp;
    private String updateReason; // "LOCATION_UPDATE", "TRAFFIC", "STOP_ARRIVAL"

}
