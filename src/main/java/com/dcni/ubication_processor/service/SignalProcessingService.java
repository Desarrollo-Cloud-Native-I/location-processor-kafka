package com.dcni.ubication_processor.service;

import com.dcni.ubication_processor.model.HorarioModel;
import com.dcni.ubication_processor.model.UbicationModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class SignalProcessingService {

    private static final String ROUTE_A = "RUTA-A";
    private static final String ROUTE_B = "RUTA-B";

    // Simulación de base de datos de paradas por ruta (Santiago, Chile)
    private final Map<String, Map<String, StopInfo>> routeStops = new HashMap<>();

    // Guardar último horario publicado por vehículo para evitar duplicados
    private final Map<String, HorarioModel> lastPublishedHorarios = new HashMap<>();

    public SignalProcessingService() {
        initializeRouteStops();
    }

    /**
     * Procesa la ubicación del vehículo y genera actualizaciones de horarios
     * Solo retorna un horario si hay cambios significativos
     * 
     * @param ubication La ubicación recibida del vehículo
     * @return HorarioModel con la actualización de horarios, o null si no hay
     *         cambios significativos
     */
    public HorarioModel processUbication(UbicationModel ubication) {
        log.info("Procesando ubicación del vehículo: {}", ubication.getVehicleId());

        // Validar datos básicos
        if (!isValidUbication(ubication)) {
            log.warn("Ubicación inválida recibida para vehículo: {}", ubication.getVehicleId());
            return null;
        }

        // Extraer o inferir la ruta del vehículo (basado en el ID del vehículo)
        String routeId = extractRouteFromVehicleId(ubication.getVehicleId());

        // Encontrar la parada más cercana
        StopInfo nearestStop = findNearestStop(routeId, ubication.getLatitude(), ubication.getLongitude());

        // Calcular distancia a la parada
        double distance = calculateDistance(
                ubication.getLatitude(), ubication.getLongitude(),
                nearestStop.latitude, nearestStop.longitude);

        // Calcular tiempo estimado de llegada basado en velocidad y distancia
        LocalDateTime estimatedArrival = calculateEstimatedArrival(
                ubication.getSpeed(), distance, ubication.getTimestamp());

        // Determinar el estado del horario
        String status = determineStatus(distance, ubication.getSpeed());

        // Calcular retraso si existe
        Integer delayMinutes = calculateDelay(nearestStop.scheduledArrival, estimatedArrival);

        // Crear la actualización de horario
        HorarioModel horario = HorarioModel.builder()
                .vehicleId(ubication.getVehicleId())
                .routeId(routeId)
                .stopId(nearestStop.stopId)
                .estimatedArrival(estimatedArrival)
                .actualArrival(distance < 0.1 ? ubication.getTimestamp() : null)
                .status(status)
                .delayMinutes(delayMinutes)
                .distanceToStop(distance)
                .timestamp(ubication.getTimestamp()) // Usar timestamp del mensaje original
                .updateReason(determineUpdateReason(distance, ubication.getSpeed()))
                .build();

        // Verificar si debe publicarse este horario
        if (!shouldPublishHorario(horario)) {
            log.debug("No hay cambios significativos para vehículo {}, se omite publicación",
                    ubication.getVehicleId());
            return null;
        }

        // Guardar como último horario publicado
        lastPublishedHorarios.put(ubication.getVehicleId(), horario);

        log.info("Horario calculado: vehicleId={}, stopId={}, status={}, ETA={}, delay={}min, distance={}km",
                horario.getVehicleId(), horario.getStopId(), horario.getStatus(),
                horario.getEstimatedArrival(), horario.getDelayMinutes(),
                String.format("%.2f", horario.getDistanceToStop()));

        return horario;
    }

    /**
     * Valida que la ubicación tenga datos correctos
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
     * Determina si el horario debe publicarse basado en cambios significativos
     */
    private boolean shouldPublishHorario(HorarioModel newHorario) {
        HorarioModel lastHorario = lastPublishedHorarios.get(newHorario.getVehicleId());

        // Primera vez, siempre publicar
        if (lastHorario == null) {
            return true;
        }

        // Publicar si cambió de parada
        if (!lastHorario.getStopId().equals(newHorario.getStopId())) {
            log.info("Cambio de parada detectado: {} -> {}", lastHorario.getStopId(), newHorario.getStopId());
            return true;
        }

        // Publicar si cambió el estado
        if (!lastHorario.getStatus().equals(newHorario.getStatus())) {
            log.info("Cambio de estado detectado: {} -> {}", lastHorario.getStatus(), newHorario.getStatus());
            return true;
        }

        // Publicar si el ETA cambió más de 2 minutos
        if (lastHorario.getEstimatedArrival() != null && newHorario.getEstimatedArrival() != null) {
            long minutesDiff = Math.abs(
                    java.time.Duration.between(lastHorario.getEstimatedArrival(),
                            newHorario.getEstimatedArrival()).toMinutes());
            if (minutesDiff >= 2) {
                log.info("Cambio significativo en ETA: {} minutos", minutesDiff);
                return true;
            }
        }

        // Publicar si está muy cerca de la parada (< 0.5 km) - actualizaciones más
        // frecuentes
        if (newHorario.getDistanceToStop() < 0.5) {
            return true;
        }

        // Publicar si llegó a la parada
        if (newHorario.getActualArrival() != null) {
            log.info("Vehículo {} llegó a parada {}", newHorario.getVehicleId(), newHorario.getStopId());
            return true;
        }

        return false;
    }

    private String extractRouteFromVehicleId(String vehicleId) {
        // Mapeando los vehículos reales del producer: VEH-001 a VEH-005
        // VEH-001, VEH-002, VEH-003 -> RUTA-A
        // VEH-004, VEH-005 -> RUTA-B
        if (vehicleId.endsWith("1") || vehicleId.endsWith("2") || vehicleId.endsWith("3")) {
            return ROUTE_A;
        } else {
            return ROUTE_B;
        }
    }

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

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        // Fórmula de Haversine para calcular distancia entre dos puntos
        final int EARTH_RADIUS = 6371; // Radio de la Tierra en km

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS * c;
    }

    private LocalDateTime calculateEstimatedArrival(Double speed, double distance, LocalDateTime currentTime) {
        if (speed == null || speed <= 0) {
            speed = 40.0; // Velocidad promedio por defecto (km/h)
        }

        // Calcular tiempo en horas y convertir a minutos
        double timeInHours = distance / speed;
        long minutesToArrive = Math.round(timeInHours * 60);

        return currentTime.plusMinutes(minutesToArrive);
    }

    private String determineStatus(double distance, Double speed) {
        // Si está muy cerca de la parada
        if (distance < 0.1) {
            return "ARRIVED";
        }

        // Si está parado
        if (speed != null && speed < 5.0) {
            return "STOPPED";
        }

        // Si está a una distancia razonable y moviéndose
        if (distance < 2.0) {
            return "APPROACHING";
        }

        // Lógica adicional basada en velocidad y horario programado
        // Por ahora, simplificado
        return "ON_TIME";
    }

    private Integer calculateDelay(LocalDateTime scheduled, LocalDateTime estimated) {
        if (scheduled == null || estimated == null) {
            return 0;
        }

        long minutesDiff = java.time.Duration.between(scheduled, estimated).toMinutes();
        return (int) minutesDiff;
    }

    private String determineUpdateReason(double distance, Double speed) {
        if (distance < 0.1) {
            return "STOP_ARRIVAL";
        } else if (speed != null && speed < 5.0) {
            return "TRAFFIC";
        } else {
            return "LOCATION_UPDATE";
        }
    }

    private void initializeRouteStops() {
        // RUTA-A
        // Paradas en Santiago, Chile (coordenadas reales alineadas con el producer)
        // Base: -33.4489, -70.6693

        // RUTA-A: Zona Norte de Santiago
        Map<String, StopInfo> rutaA = new HashMap<>();
        rutaA.put("STOP-A1", new StopInfo("STOP-A1", -33.4400, -70.6700, LocalDateTime.now().plusMinutes(10)));
        rutaA.put("STOP-A2", new StopInfo("STOP-A2", -33.4350, -70.6750, LocalDateTime.now().plusMinutes(20)));
        rutaA.put("STOP-A3", new StopInfo("STOP-A3", -33.4300, -70.6800, LocalDateTime.now().plusMinutes(30)));
        routeStops.put(ROUTE_A, rutaA);

        // RUTA-B: Zona Sur de Santiago
        Map<String, StopInfo> rutaB = new HashMap<>();
        rutaB.put("STOP-B1", new StopInfo("STOP-B1", -33.4600, -70.6650, LocalDateTime.now().plusMinutes(15)));
        rutaB.put("STOP-B2", new StopInfo("STOP-B2", -33.4650, -70.6600, LocalDateTime.now().plusMinutes(25)));
        rutaB.put("STOP-B3", new StopInfo("STOP-B3", -33.4700, -70.6550, LocalDateTime.now().plusMinutes(35)));
        routeStops.put(ROUTE_B, rutaB);

        log.info("Inicializadas {} rutas con sus paradas en Santiago, Chile", routeStops.size());
        routeStops.forEach((routeId, stops) -> log.info("  - {} con {} paradas", routeId, stops.size()));
    }

    // Clase interna para información de paradas
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
