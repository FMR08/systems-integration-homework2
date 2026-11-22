package cl.edu.integracion.orquestador.dto;

public record InventarioPasoRequest(
    String transaccionId,
    String productoId,
    Integer cantidad
) {
}
