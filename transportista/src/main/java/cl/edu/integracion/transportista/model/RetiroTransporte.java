package cl.edu.integracion.transportista.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "retiros_transporte")
public class RetiroTransporte {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transaccionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoRetiro estado;

    private String direccion;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public RetiroTransporte() {
    }

    public RetiroTransporte(final String transaccionId, final String direccion, final EstadoRetiro estado) {
        this.transaccionId = transaccionId;
        this.direccion = direccion;
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

    public EstadoRetiro getEstado() {
        return estado;
    }

    public void setEstado(final EstadoRetiro estado) {
        this.estado = estado;
    }

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(final String direccion) {
        this.direccion = direccion;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
