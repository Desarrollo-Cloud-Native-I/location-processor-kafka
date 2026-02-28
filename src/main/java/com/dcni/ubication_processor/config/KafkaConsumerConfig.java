package com.dcni.ubication_processor.config;

import com.dcni.ubication_processor.model.UbicationModel;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuración del consumidor de Kafka para recibir ubicaciones de vehículos.
 * Define el factory y listener necesarios para consumir mensajes de tipo
 * UbicationModel.
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * Crea el factory del consumidor de Kafka configurado para UbicationModel.
     * 
     * @return Factory configurado para consumir mensajes de ubicaciones
     */
    @Bean
    public ConsumerFactory<String, UbicationModel> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, UbicationModel.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Crea el listener container factory para procesar mensajes de ubicaciones.
     * Incluye manejo de errores con reintentos.
     * 
     * @return Factory configurado con manejo de errores
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, UbicationModel> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UbicationModel> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.DefaultErrorHandler(
                new org.springframework.util.backoff.FixedBackOff(1000L, 2L)));
        return factory;
    }
}
