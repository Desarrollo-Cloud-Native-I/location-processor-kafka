package com.dcni.ubication_processor.service;

import com.dcni.ubication_processor.model.HorarioModel;
import com.dcni.ubication_processor.model.UbicationModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Servicio de procesamiento de señales de ubicación de vehículos.
 * Calcula horarios estimados de llegada, detecta paradas próximas y determina
 * cuándo publicar actualizaciones significativas de horarios.
 */
@Slf4j
@Service
public class SignalProcessingService {

    private static final String ROUTE_A = "RUTA-A";
    private static final String ROUTE_B = "RUTA-B";

    private final Map<String, Map<String, StopInfo>> routeStops = new HashMap<>();
    private final Map<String, HorarioModel> lastPublishedHorarios = new HashMap<>();

    public SignalProcessingService() {
        initializeRouteStops();
    }

    /**
     * Procesa la ubicación del vehículo y genera actualizaciones de horarios.
     * Solo retorna un horario si hay cambios significativos respecto al último
     * publicado.
     * 
     * @param ubication Ubicación recibida del vehículo
     * @return Actualización de horario si hay cambios significativos, null en caso
     *         contrario
     */
    public HorarioModel processUbication(UbicationModel ubication) {
        log.info("Procesando ubicación del vehículo: {}", ubication.getVehicleId());

        if (!isValidUbication(ubication)) {
            log.warn("Ubicación inválida recibida para vehículo: {}", ubication.getVehicleId());
            return null;
        }

        String routeId = extractRouteFromVehicleId(ubication.getVehicleId());
        StopInfo nearestStop = findNearestStop(routeId, ubication.getLatitude(), ubication.getLongitude());
        double distance = calculateDistance(
                ubication.getLatitude(), ubication.getLongitude(),
                nearestStop.latitude, nearestStop.longitude);
        LocalDateTime estimatedArrival = calculateEstimatedArrival(
                ubication.getSpeed(), distance, ubication.getTimestamp());
        String status = determineStatus(distance, ubication.getSpeed());
        Integer delayMinutes = calculateDelay(nearestStop.scheduledArrival, estimatedArrival);

        HorarioModel horario = HorarioModel.builder()
                .vehicleId(ubication.getVehicleId())
                .routeId(routeId)
                .stopId(nearestStop.stopId)
                .estimatedArrival(estimatedArrival)
                .actualArrival(distance < 0.1 ? ubication.getTimestamp() : null)
                .status(status)
                .delayMinutes(delayMinutes)
                .distanceToStop(distance)
                .timestamp(ubication.getTimestamp())
                .updateReason(determineUpdateReason(distance, ubication.getSpeed()))
                .build();

        if (!shouldPublishHorario(horario)) {
            log.debug("No hay cambios significativos para vehículo {}, se omite publicación",
                    ubication.getVehicleId());
            return null;
        }

        lastPublishedHorarios.put(ubication.getVehicleId(), horario);

        log.info("Horario calculado: vehicleId={}, stopId={}, status={}, ETA={}, delay={}min, distance={}km",
                horario.getVehicleId(), horario.getStopId(), horario.getStatus(),
                horario.getEstimatedArrival(), horario.getDelayMinutes(),
                String.format("%.2f", horario.getDistanceToStop()));

        return horario;
    }

    /**
     * Valida que la ubicación tenga datos correctos.
     * 
     * @param ubication Ubicación a validar
     * @return true si la ubicación es válida, false en caso contrario
     */
    private boolean isValidUbication(UbicationModel ubication) {
        if (ubication.getVehicleId() == null || ubication.getVehicleId().trim().isEmpty()) {
            return false;
        }
        if (ubication.getLatitude() == null || ubication.getLongitude() == null) {
            return false;
        }
        if (ubication.getLatitude() < -90 || ubication.getLatitude() > 90) {
            return false;
        }
        if (ubication.getLongitude() < -180 || ubication.getLongitude() > 180) {
            return false;
        }
        if (ubication.getTimestamp() == null) {
            return false;
        }
        return true;
    }

    /**
     * Determina si el horario debe publicarse basado en cambios significativos.
     * 
     * @param newHorario Nuevo horario calculado
     * @return true si debe publicarse, false en caso contrario
     */
    private boolean shouldPublishHorario(HorarioModel newHorario) {
        HorarioModel lastHorario = lastPublishedHorarios.get(newHorario.getVehicleId());

        if (lastHorario == null) {
            return true;
        }

        if (!lastHorario.getStopId().equals(newHorario.getStopId())) {
            log.info("Cambio de parada detectado: {} -> {}", lastHorario.getStopId(), newHorario.getStopId());
            return true;
        }

        if (!lastHorario.getStatus().equals(newHorario.getStatus())) {
            log.info("Cambio de estado detectado: {} -> {}", lastHorario.getStatus(), newHorario.getStatus());
            return true;
        }

        if (lastHorario.getEstimatedArrival() != null && newHorario.getEstimatedArrival() != null) {
            long minutesDiff = Math.abs(
                    java.time.Duration.between(lastHorario.getEstimatedArrival(),
                            newHorario.getEstimatedArrival()).toMinutes());
            if (minutesDiff >= 2) {
                log.info("Cambio significativo en ETA: {} minutos", minutesDiff);
                return true;
            }
        }

        if (newHorario.getDistanceToStop() < 0.5) {
            return true;
        }

        if (newHorario.getActualArrival() != null) {
            log.info("Vehículo {} llegó a parada {}", newHorario.getVehicleId(), newHorario.getStopId());
            return true;
        }

        return false;
    }

    /**
     * Extrae el identificador de ruta basado en el ID del vehículo.
     * VEH-001, VEH-002, VEH-003 son asignados a RUTA-A.
     * VEH-004, VEH-005 son asignados a RUTA-B.
     * 
     * @param vehicleId ID del vehículo
     * @return Identificador de la ruta
     */
    private String extractRouteFromVehicleId(String vehicleId) {
        if (vehicleId.endsWith("1") || vehicleId.endsWith("2") || vehicleId.endsWith("3")) {
            return ROUTE_A;
        } else {
            return ROUTE_B;
        }
    }

    /**
     * Encuentra la parada más cercana a una ubicación dada.
     * 
     * @param routeId ID de la ruta
     * @param lat     Latitud actual
     * @param lon     Longitud actual
     * @return Información de la parada más cercana
     */
    private StopInfo findNearestStop(String routeId, double lat, double lon) {
        Map<String, StopInfo> stops = routeStops.getOrDefault(routeId, routeStops.get(ROUTE_A));

        StopInfo nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (StopInfo stop : stops.values()) {
            double distance = calculateDistance(lat, lon, stop.latitude, stop.longitude);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = stop;
            }
        }

        return nearest != null ? nearest : stops.values().iterator().next();
    }

    /**
     * Calcula la distancia entre dos puntos geográficos usando la fórmula de
     * Haversine.
     * 
     * @param lat1 Latitud del primer punto
     * @param lon1 Longitud del primer punto
     * @param lat2 Latitud del segundo punto
     * @param lon2 Longitud del segundo punto
     * @return Distancia en kilómetros
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int EARTH_RADIUS = 6371;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS * c;
    }

    /**
     * Calcula el tiempo estimado de llegada basado en velocidad y distancia.
     * 
     * @param speed       Velocidad del vehículo en km/h
     * @param distance    Distancia a la parada en km
     * @param currentTime Tiempo actual
     * @return Tiempo estimado de llegada
     */
    private LocalDateTime calculateEstimatedArrival(Double speed, double distance, LocalDateTime currentTime) {
        if (speed == null || speed <= 0) {
            speed = 40.0;
        }

        double timeInHours = distance / speed;
        long minutesToArrive = Math.round(timeInHours * 60);

        return currentTime.plusMinutes(minutesToArrive);
    }

    /**
     * Determina el estado del vehículo basado en distancia y velocidad.
     * 
     * @param distance Distancia a la parada en km
     * @param speed    Velocidad del vehículo en km/h
     * @return Estado del vehículo (ARRIVED, STOPPED, APPROACHING, ON_TIME)
     */
    private String determineStatus(double distance, Double speed) {
        if (distance < 0.1) {
            return "ARRIVED";
        }

        if (speed != null && speed < 5.0) {
            return "STOPPED";
        }

        if (distance < 2.0) {
            return "APPROACHING";
        }

        return "ON_TIME";
    }

    /**
     * Calcula el retraso en minutos entre el horario programado y el estimado.
     * 
     * @param scheduled Horario programado
     * @param estimated Horario estimado
     * @return Retraso en minutos
     */
    private Integer calculateDelay(LocalDateTime scheduled, LocalDateTime estimated) {
        if (scheduled == null || estimated == null) {
            return 0;
        }

        long minutesDiff = java.time.Duration.between(scheduled, estimated).toMinutes();
        return (int) minutesDiff;
    }

    /**
     * Determina la razón de la actualización de horario.
     * 
     * @param distance Distancia a la parada
     * @param speed    Velocidad del vehículo
     * @return Razón de la actualización
     */
    private String determineUpdateReason(double distance, Double speed) {
        if (distance < 0.1) {
            return "STOP_ARRIVAL";
        } else if (speed != null && speed < 5.0) {
            return "TRAFFIC";
        } else {
            return "LOCATION_UPDATE";
        }
    }

    /**
     * Inicializa las paradas de cada ruta con sus coordenadas en Santiago, Chile.
     */
    private void initializeRouteStops() {
        Map<String, StopInfo> rutaA = new HashMap<>();
        rutaA.put("STOP-A1", new StopInfo("STOP-A1", -33.4400, -70.6700, LocalDateTime.now().plusMinutes(10)));
        rutaA.put("STOP-A2", new StopInfo("STOP-A2", -33.4350, -70.6750, LocalDateTime.now().plusMinutes(20)));
        rutaA.put("STOP-A3", new StopInfo("STOP-A3", -33.4300, -70.6800, LocalDateTime.now().plusMinutes(30)));
        routeStops.put(ROUTE_A, rutaA);

        Map<String, StopInfo> rutaB = new HashMap<>();
        rutaB.put("STOP-B1", new StopInfo("STOP-B1", -33.4600, -70.6650, LocalDateTime.now().plusMinutes(15)));
        rutaB.put("STOP-B2", new StopInfo("STOP-B2", -33.4650, -70.6600, LocalDateTime.now().plusMinutes(25)));
        rutaB.put("STOP-B3", new StopInfo("STOP-B3", -33.4700, -70.6550, LocalDateTime.now().plusMinutes(35)));
        routeStops.put(ROUTE_B, rutaB);

        log.info("Inicializadas {} rutas con sus paradas en Santiago, Chile", routeStops.size());
        routeStops.forEach((routeId, stops) -> log.info("  - {} con {} paradas", routeId, stops.size()));
    }

    /**
     * Clase interna que almacena información de una parada.
     */
    private static class StopInfo {
        String stopId;
        double latitude;
        double longitude;
        LocalDateTime scheduledArrival;

        StopInfo(String stopId, double latitude, double longitude, LocalDateTime scheduledArrival) {
            this.stopId = stopId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.scheduledArrival = scheduledArrival;
        }
    }
}
