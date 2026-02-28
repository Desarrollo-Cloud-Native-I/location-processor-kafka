package com.dcni.ubication_processor.service;

import com.dcni.ubication_processor.model.HorarioModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Servicio productor de actualizaciones de horarios a Kafka.
 * Publica información de horarios calculados para cada vehículo.
 */
@Slf4j
@Service
public class HorarioProducerService {

    private final KafkaTemplate<String, HorarioModel> kafkaTemplate;

    @Value("${kafka.topic.horarios}")
    private String horariosTopic;

    public HorarioProducerService(KafkaTemplate<String, HorarioModel> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publica una actualización de horario en el tópico de Kafka
     * 
     * @param horario La actualización de horario a publicar
     */
    public void publishHorario(HorarioModel horario) {
        log.info("Publicando actualización de horario: vehicleId={}, routeId={}, status={}",
                horario.getVehicleId(), horario.getRouteId(), horario.getStatus());

        CompletableFuture<SendResult<String, HorarioModel>> future = kafkaTemplate.send(horariosTopic,
                horario.getVehicleId(), horario);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Horario publicado exitosamente: vehicleId={}, routeId={}, partition={}, offset={}",
                        horario.getVehicleId(),
                        horario.getRouteId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Error al publicar horario del vehículo: {}", horario.getVehicleId(), ex);
            }
        });
    }
}
