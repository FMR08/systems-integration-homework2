package cl.edu.integracion.inventario.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventario_stock")
public class Inventario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String productoId;

    @Column(nullable = false)
    private Integer stockDisponible;

    @Column(nullable = false)
    private Integer stockReservado;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Inventario() {}

    public Inventario(String productoId, Integer stockDisponible) {
        this.productoId = productoId;
        this.stockDisponible = stockDisponible;
        this.stockReservado = 0;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProductoId() { return productoId; }
    public void setProductoId(String productoId) { this.productoId = productoId; }
    public Integer getStockDisponible() { return stockDisponible; }
    public void setStockDisponible(Integer stockDisponible) { this.stockDisponible = stockDisponible; }
    public Integer getStockReservado() { return stockReservado; }
    public void setStockReservado(Integer stockReservado) { this.stockReservado = stockReservado; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
