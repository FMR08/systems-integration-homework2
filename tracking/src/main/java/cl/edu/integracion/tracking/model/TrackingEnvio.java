package cl.edu.integracion.tracking.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "tracking_envios")
public class TrackingEnvio {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transaccionId;

    @Column(nullable = false, unique = true)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoTracking estado;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public TrackingEnvio() {
    }

    public TrackingEnvio(final String transaccionId, final String trackingNumber, final EstadoTracking estado) {
        this.transaccionId = transaccionId;
        this.trackingNumber = trackingNumber;
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

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(final String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public EstadoTracking getEstado() {
        return estado;
    }

    public void setEstado(final EstadoTracking estado) {
        this.estado = estado;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
