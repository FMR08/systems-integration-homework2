package cl.edu.integracion.orquestador.dto;

public class DespachoRequest {
    private String transaccionId;
    private String productoId;
    private Integer cantidad;
    private String direccion;

    public String getTransaccionId() {
        return transaccionId;
    }

    public void setTransaccionId(final String transaccionId) {
        this.transaccionId = transaccionId;
    }

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

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(final String direccion) {
        this.direccion = direccion;
    }
}
