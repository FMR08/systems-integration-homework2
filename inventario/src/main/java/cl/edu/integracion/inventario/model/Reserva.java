package cl.edu.integracion.inventario.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoReserva estado;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public Reserva() {
    }

    public Reserva(
        final String transaccionId,
        final String productoId,
        final Integer cantidad,
        final EstadoReserva estado
    ) {
        this.transaccionId = transaccionId;
        this.productoId = productoId;
        this.cantidad = cantidad;
        this.estado = estado;
    }

    public Long getId() {
        return id;
    }

    public void setId(final Long id) {
        this.id = id;
    }

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

    public EstadoReserva getEstado() {
        return estado;
    }

    public void setEstado(final EstadoReserva estado) {
        this.estado = estado;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
