# Sistema Distribuido de Despacho Logístico (Forma L)

Arquitectura basada en microservicios con patrón **Saga Orquestada**, **API Gateway / BFF**, tolerancia a fallos y aislamiento de persistencia sobre **PostgreSQL 17** y **Docker Compose**.

---

## Arquitectura y Decisiones de Diseño Transversales

* **Aislamiento de Datos (Database-per-Service):** Cada microservicio opera sobre su propia base de datos lógica en PostgreSQL 17, eliminando el acoplamiento a nivel de esquema y garantizando autonomía de dominio.
* **Estrategia Híbrida de Contratos API (API Response Envelope):**
    * **Respuestas Exitosas Personalizadas (`2xx`):** Cada servicio define su propio DTO de éxito (`ReservaResponse`, `TransporteResponse`, `TrackingResponse`) ajustado exclusivamente a los datos de su dominio para maximizar la eficiencia de serialización y lectura del orquestador.
    * **Contrato Global de Errores (`4xx` / `5xx`):** Todos los servicios implementan un manejador global (`@RestControllerAdvice`) que estandariza los fallos bajo una estructura JSON única (`ErrorResponse` con código semántico, mensaje, timestamp y ruta), permitiendo al orquestador clasificar errores y disparar compensaciones de forma determinista.
* **Tipado Estricto de Estados mediante Enums:** Se eliminaron las cadenas mágicas (`String`) en todas las entidades y servicios, reemplazándolas por enumeraciones persistidas como texto legible (`@Enumerated(EnumType.STRING)`). Esto previene inconsistencias de estado en base de datos y errores en tiempo de ejecución.
* **Idempotencia y Resiliencia ante Concurrencia:** Cada operación transaccional exige un `transaccionId` único indexado en base de datos. Ante reintentos de red o colisiones simultáneas (`DataIntegrityViolationException`), los servicios absorben la duplicidad y retornan el estado ya persistido (`200 OK`) sin duplicar efectos secundarios.
* **Inicialización SQL Selectiva (`data.sql`):** Solo los servicios que requieren datos maestros previos para operar (como el catálogo inicial de bodega en `inventario`) ejecutan scripts de precarga automática (`spring.jpa.defer-datasource-initialization=true`). Los servicios puramente transaccionales (`transportista`, `tracking`) parten con tablas limpias.
* **Exclusión Deliberada de Swagger / OpenAPI:** Se optó por no incluir dependencias de documentación interactiva en tiempo de ejecución para mantener imágenes Docker livianas, reducir la superficie de exposición de endpoints internos en la red privada y evitar sobrecarga de arranque. La validación funcional se realiza mediante pruebas HTTP directas (`.http` / `curl`) y auditoría SQL.
* **Despliegue Modular y Automatizado:** Contenedorización mediante Docker multi-stage y scripts de reconstrucción selectiva (`rebuild.sh`) para iterar sobre servicios individuales sin reiniciar la infraestructura de datos.

---

## Catálogo de Microservicios

### 1. Microservicio de Inventario (`inventario`)
* **Estado:** Implementado y validado.
* **Base de Datos:** `inventario` (PostgreSQL 17).
* **Rol en el Negocio:** Representa el control físico de bodega. Administra el saldo de unidades disponibles frente a las unidades comprometidas para despacho, asegurando que nunca se venda mercadería inexistente.

#### Decisiones Técnicas Clave
1. **Separación de Entidades (Saldo vs. Auditoría Transaccional):**
    * `Inventario` (`inventario_stock`): Mantiene el estado maestro por producto (`productoId` único).
    * `Reserva` (`inventario_reservas`): Registra cada comando ejecutado asociado a un `transaccionId` único y su estado (`EstadoReserva.RESERVADA` / `EstadoReserva.LIBERADA`).
2. **Prevención de Condiciones de Carrera (Concurrencia):**
    * Aplica bloqueo pesimista a nivel de fila (`@Lock(LockModeType.PESSIMISTIC_WRITE)` / `SELECT ... FOR UPDATE`) al consultar el stock durante una reserva, serializando transacciones concurrentes sobre el mismo producto.
3. **Precarga Determinista (`data.sql`):**
    * Inicializa productos con stock normal y stock límite (`PROD-003` con 1 unidad) para validar escenarios de conflicto (`409 Conflict`).

#### Endpoints Expuestos (`/api/inventory`)

| Método | Ruta | Propósito en la Saga | Códigos de Respuesta |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/inventory/reserve` | **Paso Forward:** Bloquea la fila del producto, descuenta `stockDisponible`, incrementa `stockReservado` y registra la reserva como `RESERVADA`. | `200 OK` (Reservado / Idempotente)<br>`400 Bad Request` (Datos inválidos)<br>`404 Not Found` (Producto inexistente)<br>`409 Conflict` (Stock insuficiente o transacción ya liberada) |
| `POST` | `/api/inventory/release` | **Paso de Compensación:** Revierte una reserva previa ante el fallo de un paso posterior en la Saga, devolviendo unidades a `stockDisponible` y marcando como `LIBERADA`. | `200 OK` (Liberado / Idempotente)<br>`400 Bad Request` (`transaccionId` faltante)<br>`404 Not Found` (Producto asociado no encontrado) |

---

### 2. Microservicio de Transportista (`transportista`)
* **Estado:** Implementado y validado.
* **Base de Datos:** `transportista` (PostgreSQL 17).
* **Rol en el Negocio:** Representa la logística de flota y recolección. Coordina el agendamiento de vehículos de retiro en bodega hacia la dirección de entrega del cliente una vez asegurado el inventario.

#### Decisiones Técnicas Clave
1. **Ciclo de Vida Tipado (`EstadoRetiro`):**
    * Controla la orden de recogida mediante estados explícitos (`AGENDADO` y `CANCELADO`) en la tabla `retiros_transporte`.
2. **Contención de Colisiones Simultáneas:**
    * Además de verificar previamente el `transaccionId`, captura `DataIntegrityViolationException` en el controlador ante inserciones concurrentes idénticas, reintentando la lectura para entregar una respuesta idempotente limpia (`200 OK`).
3. **Validación Estricta de Destino:**
    * Rechaza solicitudes sin `transaccionId` o `direccion` válida (`400 Bad Request`) antes de comprometer recursos logísticos.

#### Endpoints Expuestos (`/api/shipping`)

| Método | Ruta | Propósito en la Saga | Códigos de Respuesta |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/shipping/schedule` | **Paso Forward:** Registra y agenda el retiro hacia la dirección indicada bajo estado `AGENDADO`. | `200 OK` (Agendado / Idempotente)<br>`400 Bad Request` (`transaccionId` o `direccion` faltante)<br>`409 Conflict` (Transacción previamente cancelada) |
| `POST` | `/api/shipping/cancel` | **Paso de Compensación:** Anula la orden de retiro pasando su estado a `CANCELADO` si ocurre un fallo posterior en la Saga. | `200 OK` (Cancelado / Idempotente)<br>`400 Bad Request` (`transaccionId` faltante) |

---

### 3. Microservicio de Tracking (`tracking`)
* **Estado:** Implementado y validado.
* **Base de Datos:** `tracking` (PostgreSQL 17).
* **Rol en el Negocio:** Representa la trazabilidad y visibilidad hacia el cliente final. Emite un identificador único de seguimiento (`TRK-XXXXXXXX`) que permite monitorear el estado del despacho desde su creación hasta su entrega o anulación.

#### Decisiones Técnicas Clave
1. **Modelado de Estados Logísticos (`EstadoTracking`):**
    * Define el ciclo completo de seguimiento (`GENERADO`, `EN_TRANSITO`, `ENTREGADO`, `ANULADO`), soportando tanto la emisión transaccional en la Saga como futuras consultas de estado desde el BFF.
2. **Idempotencia con Preservación de Identificador:**
    * Ante reintentos de red o colisiones concurrentes (`DataIntegrityViolationException`) sobre el mismo `transaccionId`, recupera y devuelve exactamente el mismo `trackingNumber` emitido originalmente, evitando códigos huérfanos o duplicados.

#### Endpoints Expuestos (`/api/tracking`)

| Método | Ruta | Propósito en la Saga | Códigos de Respuesta |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/tracking/generate` | **Paso Forward:** Genera un código único (`TRK-...`), lo persiste con estado `GENERADO` y lo retorna en `TrackingResponse`. | `200 OK` (Generado / Idempotente)<br>`400 Bad Request` (`transaccionId` faltante)<br>`409 Conflict` (Tracking previamente anulado) |
| `POST` | `/api/tracking/cancel` | **Paso de Compensación:** Invalida el código de seguimiento cambiando su estado a `ANULADO`. | `200 OK` (Anulado / Idempotente)<br>`400 Bad Request` (`transaccionId` faltante) |
