package com.dcni.ubication_processor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UbicationModel {

    private String vehicleId;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private LocalDateTime timestamp;
    private String status;

}
