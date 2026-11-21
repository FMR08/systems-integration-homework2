package cl.edu.integracion.transportista.dto;

import cl.edu.integracion.transportista.model.EstadoRetiro;

public record RetiroDetalleResponse(String transaccionId, EstadoRetiro estado, String direccion) {}
