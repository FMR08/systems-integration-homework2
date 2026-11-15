package cl.edu.integracion.orquestador.dto;

public class InventarioPasoRequest {
    private String transaccionId;
    private String productoId;
    private Integer cantidad;

    public InventarioPasoRequest(String transaccionId, String productoId, Integer cantidad) {
        this.transaccionId = transaccionId;
        this.productoId = productoId;
        this.cantidad = cantidad;
    }

    public String getTransaccionId() { return transaccionId; }
    public String getProductoId() { return productoId; }
    public Integer getCantidad() { return cantidad; }
}
