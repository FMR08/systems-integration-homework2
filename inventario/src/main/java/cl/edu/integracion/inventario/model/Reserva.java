package cl.edu.integracion.inventario.model;

import jakarta.persistence.*;

@Entity
@Table(name = "inventario_reservas")
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transaccionId;

    @Column(nullable = false)
    private String productoId;

    @Column(nullable = false)
    private Integer cantidad;

    @Column(nullable = false)
    private String estado;

    public Reserva() {}

    public Reserva(String transaccionId, String productoId, Integer cantidad, String estado) {
        this.transaccionId = transaccionId;
        this.productoId = productoId;
        this.cantidad = cantidad;
        this.estado = estado;
    }

    public String getTransaccionId() { return transaccionId; }
    public String getProductoId() { return productoId; }
    public Integer getCantidad() { return cantidad; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
