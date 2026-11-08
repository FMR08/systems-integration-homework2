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

    @Column(nullable = false)
    private String estado;

    private String direccion;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public RetiroTransporte() {}

    public RetiroTransporte(String transaccionId, String direccion, String estado) {
        this.transaccionId = transaccionId;
        this.direccion = direccion;
        this.estado = estado;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTransaccionId() { return transaccionId; }
    public void setTransaccionId(String transaccionId) { this.transaccionId = transaccionId; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
