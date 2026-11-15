package cl.edu.integracion.orquestador.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "despachos_saga")
public class Despacho {

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
    private String direccion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoDespacho estado;

    private String trackingNumber;

    @Column(length = 500)
    private String detalleError;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Despacho() {}

    public Despacho(String transaccionId, String productoId, Integer cantidad, String direccion) {
        this.transaccionId = transaccionId;
        this.productoId = productoId;
        this.cantidad = cantidad;
        this.direccion = direccion;
        this.estado = EstadoDespacho.INICIADA;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTransaccionId() { return transaccionId; }
    public void setTransaccionId(String transaccionId) { this.transaccionId = transaccionId; }
    public String getProductoId() { return productoId; }
    public void setProductoId(String productoId) { this.productoId = productoId; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
    public EstadoDespacho getEstado() { return estado; }
    public void setEstado(EstadoDespacho estado) { this.estado = estado; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public String getDetalleError() { return detalleError; }
    public void setDetalleError(String detalleError) { this.detalleError = detalleError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
