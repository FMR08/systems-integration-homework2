INSERT INTO inventario_stock (producto_id, stock_disponible, stock_reservado, updated_at)
VALUES
    ('PROD-001', 100, 0, NOW()),
    ('PROD-002', 50, 0, NOW()),
    ('PROD-003', 1, 0, NOW())
    ON CONFLICT (producto_id) DO NOTHING;
