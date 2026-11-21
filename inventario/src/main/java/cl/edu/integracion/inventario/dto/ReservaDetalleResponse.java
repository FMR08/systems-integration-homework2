package cl.edu.integracion.inventario.dto;

import cl.edu.integracion.inventario.model.EstadoReserva;

public record ReservaDetalleResponse(String transaccionId, String productoId,
                                     Integer cantidad, EstadoReserva estado) {}
