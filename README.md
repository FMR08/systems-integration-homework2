# Sistema Distribuido de Despacho Logístico (Forma L)

Arquitectura basada en microservicios con patrón **Saga Orquestada**, **API Gateway / BFF**, tolerancia a fallos (**Resilience4j**) y aislamiento de persistencia sobre **PostgreSQL 17** y **Docker Compose**.

---

## Arquitectura y Decisiones de Diseño Transversales

- **Aislamiento de Datos (Database-per-Service):** Cada microservicio opera sobre su propia base de datos lógica en PostgreSQL 17, eliminando el acoplamiento a nivel de esquema y garantizando autonomía de dominio.
- **Punto Único de Entrada y Aislamiento de Red:** En `docker-compose.yml`, únicamente el **API Gateway** expone el puerto `8080` hacia el exterior. Los microservicios internos (`inventario`, `transportista`, `tracking`, `orquestador`, `bff`) operan exclusivamente dentro de la red privada de Docker sin puertos publicados al host.
- **Estrategia Híbrida de Contratos API (API Response Envelope):**
  - **Respuestas Exitosas Personalizadas (`2xx`):** Cada servicio define su propio DTO de éxito (`ReservaResponse`, `TransporteResponse`, `TrackingResponse`, `DespachoResponse`) ajustado exclusivamente a los datos de su dominio para maximizar la eficiencia de serialización y lectura del orquestador.
  - **Contrato Global de Errores (`4xx` / `5xx`):** Todos los servicios —incluido el API Gateway— estandarizan los fallos bajo una estructura JSON única (`ErrorResponse` con código semántico, mensaje, timestamp y ruta), permitiendo clasificar errores y disparar compensaciones de forma determinista.
- **Tipado Estricto de Estados mediante Enums:** Se eliminaron las cadenas mágicas (`String`) en todas las entidades y servicios, reemplazándolas por enumeraciones persistidas como texto legible (`@Enumerated(EnumType.STRING)`). Esto previene inconsistencias de estado en base de datos y errores en tiempo de ejecución.
- **Idempotencia y Resiliencia ante Concurrencia:** Cada operación transaccional exige un `transaccionId` único indexado en base de datos. Ante reintentos de red o colisiones simultáneas (`DataIntegrityViolationException`), los servicios absorben la duplicidad y retornan el estado ya persistido (`200 OK`) sin duplicar efectos secundarios.
- **Inicialización SQL Selectiva (`data.sql`):** Solo los servicios que requieren datos maestros previos para operar (como el catálogo inicial de bodega en `inventario`) ejecutan scripts de precarga automática (`spring.jpa.defer-datasource-initialization=true`). Los servicios puramente transaccionales (`transportista`, `tracking`, `orquestador`) parten con tablas limpias.
- **Chaos Engineering Controlado por HTTP:** Los servicios susceptibles a degradación o fallo en los escenarios de evaluación (`transportista` y `tracking`) incorporan controladores dedicados (`/chaos`) para inyectar latencia artificial o fallos HTTP en caliente sin detener contenedores ni comprometer los endpoints de compensación.
- **Exclusión Deliberada de Swagger / OpenAPI:** Se optó por no incluir dependencias de documentación interactiva en tiempo de ejecución para mantener imágenes Docker livianas, reducir la superficie de exposición de endpoints internos en la red privada y evitar sobrecarga de arranque. La validación funcional se realiza mediante pruebas HTTP directas (`.http` / `curl`) y auditoría SQL.
- **Despliegue Modular y Automatizado:** Contenedorización mediante Docker multi-stage y scripts de reconstrucción selectiva (`rebuild.sh`) para iterar sobre servicios individuales sin reiniciar la infraestructura de datos.

---

## Catálogo de Componentes y Microservicios

### 1. API Gateway (`gateway`)
- **Estado:** Implementado y validado.
- **Tecnología:** Spring Cloud Gateway (Netty reactivo WebFlux, sin base de datos ni lógica de negocio).
- **Puerto Expuesto:** `8080` (único punto de acceso externo al ecosistema).
- **Rol en la Arquitectura:** Centraliza el enrutamiento hacia los servicios internos, aplica autenticación perimetral y protege al sistema contra picos de tráfico mediante limitación de tasa por cliente.

#### Decisiones Técnicas Clave
1. **Separación Estricta de Responsabilidades:**
   - No contiene reglas de negocio ni persistencia; actúa exclusivamente como proxy inverso inteligente con cadena de filtros globales ordenados (`Ordered`).
2. **Autenticación Centralizada (`AuthFilter`, orden `-2`):**
   - Intercepta todas las peticiones entrantes (excepto `/actuator/**` para lectura rápida de métricas) exigiendo la cabecera `Authorization: Bearer <GATEWAY_TOKEN>`. Ante ausencia o invalidez del token, corta la petición de inmediato con `401 Unauthorized` y el formato estándar `ErrorResponse`.
3. **Rate Limiting en Memoria sin Dependencias Externas (`RateLimitFilter`, orden `-1`):**
   - Se ejecuta inmediatamente después de autenticar al cliente. Contabiliza peticiones por ventana de minuto (`ConcurrentHashMap` + `AtomicInteger`) identificando al consumidor mediante `X-Client-Id` o su IP remota, inyectando las cabeceras informativas `X-RateLimit-Limit` y `X-RateLimit-Remaining`, y bloqueando excesos con `429 Too Many Requests` (`RATE_LIMIT_EXCEEDED`). Esta implementación evita incorporar un contenedor adicional de Redis manteniendo el diseño minimalista.

#### Tabla de Enrutamiento (`application.yml`)

| ID de Ruta | Predicado de Entrada | Destino Interno | Propósito |
| :--- | :--- | :--- | :--- |
| `orquestador-despachos` | `Path=/api/despachos/**` | `http://orquestador:8080` | Creación y consulta de envíos (Saga Orquestada). |
| `bff-envios` | `Path=/api/bff/**` | `http://bff:8080` | Consulta agregada para clientes (BFF). |
| `orquestador-actuator` | `Path=/actuator/**` | `http://orquestador:8080` | Inspección en vivo de Circuit Breaker y Bulkhead. |
| `transportista-chaos` | `Path=/api/shipping/chaos/**` | `http://transportista:8080` | Control de latencia y caída en el servicio frágil. |
| `tracking-chaos` | `Path=/api/tracking/chaos/**` | `http://tracking:8080` | Control de falla inducida en el último paso de la Saga. |

---

### 2. Orquestador de Despachos (`orquestador`)
- **Estado:** Implementado, endurecido con Resilience4j y validado end-to-end vía Gateway.
- **Base de Datos:** `despachos` (PostgreSQL 17, acceso exclusivo interno).
- **Rol en el Negocio:** Actúa como el coordinador central de la transacción distribuida (**Patrón Saga Orquestada**). Recibe la solicitud de creación de envío, dirige la ejecución secuencial sobre los tres dominios, persiste el progreso paso a paso y ejecuta compensaciones en orden inverso ante fallos o caídas de red.

#### Evolución y Decisiones Técnicas Clave
1. **Máquina de Estados Persistente (`EstadoDespacho` en `despachos_saga`):**
   - Evolucionó desde un flujo lineal básico hacia una bitácora transaccional granular que confirma en PostgreSQL cada transición antes de ejecutar la siguiente llamada remota:
     `INICIADA` $\rightarrow$ `INVENTARIO_RESERVADO` $\rightarrow$ `TRANSPORTE_AGENDADO` $\rightarrow$ `TRACKING_GENERADO` $\rightarrow$ `COMPLETADA`.
   - Ante fallos, transita a `COMPENSANDO` y distingue explícitamente entre `COMPENSADA` (rollback limpio en todos los pasos confirmados) y `FALLIDA` (rollback incompleto que requiere auditoría manual).
2. **Compensación Preventiva en Orden Inverso:**
   - Las banderas de ejecución se marcan antes de invocar cada microservicio remoto para cubrir escenarios de *Read Timeout* (donde el servicio destino sí persistió el cambio pero la respuesta no llegó a tiempo). Apoyándose en la idempotencia y cancelación preventiva de los servicios de dominio, el orquestador revierte en orden estricto: `Tracking` $\rightarrow$ `Transporte` $\rightarrow$ `Inventario`.
3. **Aislamiento de la Dependencia Frágil (`TransportistaClient` + Resilience4j):**
   - **Timeouts Diferenciados:** El agendamiento (`agendar`) opera con timeout corto (`connect=1s`, `read=2s`) para fallar rápido; la compensación (`cancelar`) opera con mayor tolerancia (`connect=2s`, `read=4s`), fuera del Circuit Breaker, y con **3 reintentos y backoff exponencial** (`500ms`, `1000ms`, `2000ms`).
   - **Circuit Breaker (`transportista`):** Ventana deslizante de 6 llamadas (`minimum-number-of-calls=4`), umbral de fallo o lentitud del `50%` y apertura por `15s` hacia un `fallback` (`503 Service Unavailable`) que dispara la compensación inmediata de inventario (*fail-fast*). Ignora errores `4xx` de negocio (`HttpClientErrorException`) para no abrir el circuito por datos inválidos del cliente.
   - **Bulkhead (`SEMAPHORE`):** Limita a un máximo de **3 llamadas concurrentes** hacia `transportista` sin cola de espera (`max-wait-duration=0`), evitando el agotamiento de hilos del servidor ante picos de carga.
4. **Observabilidad (`Spring Boot Actuator`):**
   - Expone `/actuator/health`, `/actuator/metrics`, `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents` y `/actuator/bulkheads` para inspeccionar en vivo las transiciones del circuito (`CLOSED` $\rightarrow$ `OPEN` $\rightarrow$ `HALF_OPEN`) y los rechazos del Bulkhead.

#### Nota Técnica para Pruebas de Latencia (Calibración Timeout vs. Slow Call)
En `TransportistaClient`, el cliente HTTP tiene `readTimeout = 2000ms`, mientras que el Circuit Breaker evalúa `slow-call-duration-threshold = 1000ms` sobre una ventana mínima de `4` llamadas:
- **Latencia intermedia (`1000ms < delayMs < 2000ms`, ej. `1500ms`):** Las primeras 4 peticiones **completan el envío con éxito (`200 OK`)** porque no superan el `readTimeout` de `2s`, pero Resilience4j las contabiliza como *llamadas lentas*. Al completarse la 4ª llamada, el circuito transiciona a `OPEN` y a partir de la 5ª petición rechaza al instante (*fail-fast*). Lanzar menos de 4 peticiones con `delayMs=1500` producirá éxitos sin abrir el circuito.
- **Latencia severa (`delayMs > 2000ms`, ej. `3000ms`):** Cada llamada dispara inmediatamente `ReadTimeout` a los `2s`, activa el fallback y compensa el inventario desde el primer intento, abriendo el circuito tras 4 fallos.

#### Endpoints Expuestos (`/api/despachos`)

| Método | Ruta | Propósito | Códigos de Respuesta |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/despachos` | **Orquestación Saga:** Ejecuta la creación del envío coordinando `inventario`, `transportista` y `tracking` con compensación automática. | `200 OK` (Completada / Idempotente)<br>`400 Bad Request` (Solicitud inválida)<br>`409 Conflict` (Transacción ya fallida/compensada o en curso)<br>`502 Bad Gateway` (Fallo en paso de Saga + compensación)<br>`503 Service Unavailable` (Transportista no disponible / Circuito abierto) |
| `GET` | `/api/despachos/{transaccionId}` | **Consulta de Estado:** Recupera el estado actual de la bitácora de la Saga y el `trackingNumber` si fue generado. | `200 OK` (Estado recuperado)<br>`404 Not Found` (`transaccionId` inexistente) |

---

### 3. Microservicio de Inventario (`inventario`)
- **Estado:** Implementado y validado.
- **Base de Datos:** `inventario` (PostgreSQL 17, acceso exclusivo interno).
- **Rol en el Negocio:** Representa el control físico de bodega. Administra el saldo de unidades disponibles frente a las unidades comprometidas para despacho, asegurando que nunca se venda mercadería inexistente.

#### Decisiones Técnicas Clave
1. **Separación de Entidades (Saldo vs. Auditoría Transaccional):**
   - `Inventario` (`inventario_stock`): Mantiene el estado maestro por producto (`productoId` único).
   - `Reserva` (`inventario_reservas`): Registra cada comando ejecutado asociado a un `transaccionId` único y su estado (`EstadoReserva.RESERVADA` / `EstadoReserva.LIBERADA`).
2. **Prevención de Condiciones de Carrera (Concurrencia):**
   - Aplica bloqueo pesimista a nivel de fila (`@Lock(LockModeType.PESSIMISTIC_WRITE)` / `SELECT ... FOR UPDATE`) al consultar el stock durante una reserva, serializando transacciones concurrentes sobre el mismo producto.
3. **Precarga Determinista (`data.sql`):**
   - Inicializa productos con stock normal y stock límite (`PROD-003` con 1 unidad) para validar escenarios de conflicto (`409 Conflict`).

#### Endpoints Internos (`/api/inventory`)

| Método | Ruta | Propósito en la Saga | Códigos de Respuesta |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/inventory/reserve` | **Paso Forward:** Bloquea la fila del producto, descuenta `stockDisponible`, incrementa `stockReservado` y registra la reserva como `RESERVADA`. | `200 OK` (Reservado / Idempotente)<br>`400 Bad Request` (Datos inválidos)<br>`404 Not Found` (Producto inexistente)<br>`409 Conflict` (Stock insuficiente o transacción ya liberada) |
| `POST` | `/api/inventory/release` | **Paso de Compensación:** Revierte una reserva previa ante el fallo de un paso posterior en la Saga, devolviendo unidades a `stockDisponible` y marcando como `LIBERADA`. | `200 OK` (Liberado / Idempotente)<br>`400 Bad Request` (`transaccionId` faltante)<br>`404 Not Found` (Producto asociado no encontrado) |

---

### 4. Microservicio de Transportista (`transportista`)
- **Estado:** Implementado y validado (incluye módulo Chaos Engineering).
- **Base de Datos:** `transportista` (PostgreSQL 17, acceso exclusivo interno).
- **Rol en el Negocio:** Representa la logística de flota y recolección externa (identificada como la dependencia frágil del dominio). Coordina el agendamiento de vehículos de retiro en bodega hacia la dirección de entrega del cliente una vez asegurado el inventario.

#### Decisiones Técnicas Clave
1. **Ciclo de Vida Tipado (`EstadoRetiro`):**
   - Controla la orden de recogida mediante estados explícitos (`AGENDADO` y `CANCELADO`) en la tabla `retiros_transporte`.
2. **Cancelación Preventiva (Blindaje ante Timeouts):**
   - Si el orquestador envía una compensación (`/cancel`) para un `transaccionId` cuyo agendamiento nunca llegó a persistirse (por ejemplo, tras un timeout de red), el servicio registra inmediatamente el `transaccionId` con estado `CANCELADO`. Esto bloquea cualquier petición tardía de `/schedule` que pudiera llegar rezagada por la red.
3. **Contención de Colisiones Simultáneas:**
   - Captura `DataIntegrityViolationException` en el controlador ante inserciones concurrentes idénticas, reintentando la lectura para entregar una respuesta idempotente limpia (`200 OK`).
4. **Inyección de Fallas Controlada (`ChaosController`):**
   - Permite inyectar latencia artificial (`delayMs`) o caída total (`down=true` -> `503 Service Unavailable`) exclusivamente en `/schedule`, manteniendo `/cancel` operativo para garantizar la ejecución del rollback.

#### Endpoints (`/api/shipping`)

| Método | Ruta | Propósito | Códigos de Respuesta |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/shipping/schedule` | **Paso Forward:** Registra y agenda el retiro hacia la dirección indicada bajo estado `AGENDADO`. | `200 OK` (Agendado / Idempotente)<br>`400 Bad Request` (Datos faltantes)<br>`409 Conflict` (Transacción cancelada)<br>`503 Service Unavailable` (Caos activo) |
| `POST` | `/api/shipping/cancel` | **Paso de Compensación:** Anula la orden de retiro pasando su estado a `CANCELADO` (o la marca preventivamente como cancelada). | `200 OK` (Cancelado / Idempotente)<br>`400 Bad Request` (`transaccionId` faltante) |
| `POST` | `/api/shipping/chaos` | **Pruebas de Resiliencia (vía Gateway):** Configura latencia (`delayMs`) y disponibilidad (`down`) en caliente. | `200 OK` (`{"delayMs": ..., "down": ...}`) |
| `GET` | `/api/shipping/chaos` | **Pruebas de Resiliencia (vía Gateway):** Consulta el estado actual de inyección de fallos. | `200 OK` (`{"delayMs": ..., "down": ...}`) |

---

### 5. Microservicio de Tracking (`tracking`)
- **Estado:** Implementado y validado (incluye módulo Chaos Engineering).
- **Base de Datos:** `tracking` (PostgreSQL 17, acceso exclusivo interno).
- **Rol en el Negocio:** Representa la trazabilidad y visibilidad hacia el cliente final. Emite un identificador único de seguimiento (`TRK-XXXXXXXX`) que permite monitorear el estado del despacho desde su creación hasta su entrega o anulación.

#### Decisiones Técnicas Clave
1. **Modelado de Estados Logísticos (`EstadoTracking`):**
   - Define el ciclo completo de seguimiento (`GENERADO`, `EN_TRANSITO`, `ENTREGADO`, `ANULADO`), soportando tanto la emisión transaccional en la Saga como consultas de estado desde el BFF.
2. **Idempotencia con Preservación de Identificador:**
   - Ante reintentos de red o colisiones concurrentes (`DataIntegrityViolationException`) sobre el mismo `transaccionId`, recupera y devuelve exactamente el mismo `trackingNumber` emitido originalmente, evitando códigos huérfanos o duplicados.
3. **Inyección de Fallas en Paso Final (`ChaosController`):**
   - Permite forzar un error interno (`500 Internal Server Error`) durante la generación del código para validar el escenario de compensación inversa completa desde el último eslabón de la Saga.

#### Endpoints (`/api/tracking`)

| Método | Ruta | Propósito | Códigos de Respuesta |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/tracking/generate` | **Paso Forward:** Genera un código único (`TRK-...`), lo persiste con estado `GENERADO` y lo retorna en `TrackingResponse`. | `200 OK` (Generado / Idempotente)<br>`400 Bad Request` (`transaccionId` faltante)<br>`409 Conflict` (Tracking previamente anulado)<br>`500 Internal Server Error` (Caos activo) |
| `POST` | `/api/tracking/cancel` | **Paso de Compensación:** Invalida el código de seguimiento cambiando su estado a `ANULADO`. | `200 OK` (Anulado / Idempotente)<br>`400 Bad Request` (`transaccionId` faltante) |
| `POST` | `/api/tracking/chaos` | **Pruebas de Resiliencia (vía Gateway):** Activa o desactiva la falla inducida (`fail=true|false`). | `200 OK` (`{"fallarGenerar": ...}`) |
| `GET` | `/api/tracking/chaos` | **Pruebas de Resiliencia (vía Gateway):** Consulta el estado actual del interruptor de falla. | `200 OK` (`{"fallarGenerar": ...}`) |

---

## Ejecución y Validación Rápida (Vía API Gateway)

1. **Levantar la infraestructura completa:**
   ```bash
   docker compose up -d --build
   