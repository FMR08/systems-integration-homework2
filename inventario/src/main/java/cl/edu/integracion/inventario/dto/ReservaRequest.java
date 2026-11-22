package cl.edu.integracion.inventario.dto;

public class ReservaRequest {
    private String productoId;
    private Integer cantidad;
    private String transaccionId;

    public String getProductoId() {
        return productoId;
    }

    public void setProductoId(final String productoId) {
        this.productoId = productoId;
    }

    public Integer getCantidad() {
        return cantidad;
    }

    public void setCantidad(final Integer cantidad) {
        this.cantidad = cantidad;
    }

    public String getTransaccionId() {
        return transaccionId;
    }

    public void setTransaccionId(final String transaccionId) {
        this.transaccionId = transaccionId;
    }
}
