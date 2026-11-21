package cl.edu.integracion.tracking.dto;

import cl.edu.integracion.tracking.model.EstadoTracking;

public record TrackingDetalleResponse(String transaccionId, String trackingNumber, EstadoTracking estado) {}
