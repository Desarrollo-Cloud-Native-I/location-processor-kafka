package com.dcni.ubication_processor.service;

import com.dcni.ubication_processor.model.HorarioModel;
import com.dcni.ubication_processor.model.UbicationModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Servicio consumidor de ubicaciones de vehículos desde Kafka.
 * Procesa cada ubicación recibida, calcula actualizaciones de horarios y
 * publica los resultados en el tópico de horarios.
 */
@Slf4j
@Service
public class UbicationConsumerService {

    private final SignalProcessingService signalProcessingService;
    private final HorarioProducerService horarioProducerService;

    public UbicationConsumerService(SignalProcessingService signalProcessingService,
            HorarioProducerService horarioProducerService) {
        this.signalProcessingService = signalProcessingService;
        this.horarioProducerService = horarioProducerService;
    }

    /**
     * Consume mensajes del tópico de ubicaciones de vehículos.
     * Procesa cada ubicación, calcula horarios y publica actualizaciones si hay
     * cambios significativos.
     * 
     * @param ubication Ubicación del vehículo recibida desde Kafka
     */
    @KafkaListener(topics = "${kafka.topic.ubicaciones}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeUbication(UbicationModel ubication) {
        log.info("========================================");
        log.info("Mensaje recibido del tópico ubicaciones_vehiculos");
        log.info("VehicleId: {}", ubication.getVehicleId());
        log.info("Ubicación: [{}, {}]", ubication.getLatitude(), ubication.getLongitude());
        log.info("Velocidad: {} km/h", ubication.getSpeed());
        log.info("Estado: {}", ubication.getStatus());
        log.info("Timestamp: {}", ubication.getTimestamp());

        try {
            HorarioModel horario = signalProcessingService.processUbication(ubication);

            if (horario != null) {
                horarioProducerService.publishHorario(horario);
                log.info("✅ Horario publicado para vehículo: {}", ubication.getVehicleId());
            } else {
                log.debug("⏭️ Sin cambios significativos para vehículo: {} - no se publica horario",
                        ubication.getVehicleId());
            }

        } catch (Exception e) {
            log.error("❌ Error al procesar ubicación del vehículo: {}", ubication.getVehicleId(), e);
        }

        log.info("========================================");
    }
}
