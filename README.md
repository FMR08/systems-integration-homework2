# Sistema Distribuido de Despacho Logístico (Forma L)

Arquitectura basada en microservicios con patrón **Saga Orquestada**, **API Gateway / BFF**, tolerancia a fallos (**Resilience4j**) y aislamiento de persistencia sobre **PostgreSQL 17** y **Docker Compose**.

---

## Arquitectura y Decisiones de Diseño Transversales

- **Aislamiento de Datos (Database-per-Service):** Cada microservicio opera sobre su propia base de datos lógica en PostgreSQL 17, eliminando el acoplamiento a nivel de esquema y garantizando autonomía de dominio.
- **Punto Único de Entrada y Aislamiento de Red:** En `docker-compose.yml`, únicamente el **API Gateway** expone el puerto `8080` hacia el exterior. Los microservicios internos (`inventario`, `transportista`, `tracking`, `orquestador`, `bff`) operan exclusivamente dentro de la red privada de Docker sin puertos publicados al host (tampoco PostgreSQL; para auditoría SQL: `docker compose exec postgres psql -U integracion -d <inventario|transportista|tracking|despachos>`).
- **Estrategia Híbrida de Contratos API (API Response Envelope):**
  - **Respuestas Exitosas Personalizadas (`2xx`):** Cada servicio define su propio DTO de éxito (`ReservaResponse`, `TransporteResponse`, `TrackingResponse`, `DespachoResponse`) ajustado exclusivamente a los datos de su dominio para maximizar la eficiencia de serialización y lectura del orquestador.
  - **Contrato Global de Errores (`4xx` / `5xx`):** Todos los servicios —incluido el API Gateway— estandarizan los fallos bajo una estructura JSON única (`ErrorResponse` con código semántico, mensaje, timestamp y ruta), permitiendo clasificar errores y disparar compensaciones de forma determinista.
- **Tipado Estricto de Estados mediante Enums:** Se eliminaron las cadenas mágicas (`String`) en todas las entidades y servicios, reemplazándolas por enumeraciones persistidas como texto legible (`@Enumerated(EnumType.STRING)`). Esto previene inconsistencias de estado en base de datos y errores en tiempo de ejecución.
- **Idempotencia y Resiliencia ante Concurrencia:** Cada operación transaccional exige un `transaccionId` único indexado en base de datos. Ante reintentos de red o colisiones simultáneas (`DataIntegrityViolationException`), los servicios absorben la duplicidad y retornan el estado ya persistido (`200 OK`) sin duplicar efectos secundarios.
- **Inicialización SQL Selectiva (`data.sql`):** Solo los servicios que requieren datos maestros previos para operar (como el catálogo inicial de bodega en `inventario`) ejecutan scripts de precarga automática (`spring.jpa.defer-datasource-initialization=true`). Los servicios puramente transaccionales (`transportista`, `tracking`, `orquestador`) parten con tablas limpias.
- **Chaos Engineering Controlado por HTTP:** Los servicios susceptibles a degradación o fallo en los escenarios de evaluación (`transportista` y `tracking`) incorporan controladores dedicados (`/chaos`) para inyectar latencia artificial o fallos HTTP en caliente sin detener contenedores ni comprometer los endpoints de compensación.
- **Exclusión Deliberada de Swagger / OpenAPI:** Se optó por no incluir dependencias de documentación interactiva en tiempo de ejecución para mantener imágenes Docker livianas, reducir la superficie de exposición de endpoints internos en la red privada y evitar sobrecarga de arranque. La validación funcional se realiza mediante pruebas HTTP directas (`.http` / `curl`) y auditoría SQL.
- **Despliegue Modular y Automatizado:** Build con **Gradle (Kotlin DSL)** multi-proyecto y catálogo de versiones (`gradle/libs.versions.toml`). Contenedorización con Docker multi-stage, caché de BuildKit para Gradle (`--mount=type=cache`) y scripts de reconstrucción selectiva (`rebuild.sh`) para iterar sobre servicios individuales sin reiniciar la infraestructura de datos.

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
   - Intercepta todas las peticiones entrantes (incluido `/actuator/**`) exigiendo la cabecera `Authorization: Bearer <GATEWAY_TOKEN>`. Ante ausencia o invalidez del token, corta la petición de inmediato con `401 Unauthorized` y el formato estándar `ErrorResponse`.
3. **Rate Limiting en Memoria sin Dependencias Externas (`RateLimitFilter`, orden `-1`):**
   - Se ejecuta inmediatamente después de autenticar al cliente. Contabiliza peticiones por ventana de minuto (`ConcurrentHashMap` + `AtomicInteger`) identificando al consumidor mediante `X-Client-Id` o su IP remota, inyectando las cabeceras informativas `X-RateLimit-Limit` y `X-RateLimit-Remaining`, y bloqueando excesos con `429 Too Many Requests` (`RATE_LIMIT_EXCEEDED`). Las rutas `/actuator/**` quedan exentas del límite (solo del límite, no de la autenticación). Esta implementación evita incorporar un contenedor adicional de Redis manteniendo el diseño minimalista.
4. **Timeouts hacia los servicios internos:** `connect-timeout=2s` y `response-timeout=30s` (cubre el peor caso de una saga con compensación y reintentos).

#### Tabla de Enrutamiento (`application.yml`)

| ID de Ruta | Predicado de Entrada | Destino Interno | Propósito |
| :--- | :--- | :--- | :--- |
| `orquestador-despachos` | `Path=/api/despachos/**` | `http://orquestador:8080` | Creación y consulta de envíos (Saga Orquestada). |
| `bff-envios` | `Path=/api/bff/**` | `http://bff:8080` | Consulta agregada para clientes (BFF). |
| `orquestador-actuator` | `Path=/actuator/**` | `http://orquestador:8080` | Inspección en vivo de Circuit Breaker y Bulkhead. |
| `transportista-chaos` | `Path=/api/shipping/chaos/**` | `http://transportista:8080` | Control de latencia y caída en el servicio frágil. |
| `tracking-chaos` | `Path=/api/tracking/chaos/**` | `http://tracking:8080` | Control de falla inducida en el último paso de la Saga. |
| `bff-actuator` | `Path=/bff-actuator/**` (reescrito a `/actuator/**`) | `http://bff:8080` | Métricas y estado de Circuit Breaker / Bulkhead del BFF. |

---

### 2. Orquestador de Despachos (`orquestador`)
- **Estado:** Implementado, endurecido con Resilience4j y validado end-to-end vía Gateway.
- **Base de Datos:** `despachos` (PostgreSQL 17, acceso exclusivo interno).
- **Rol en el Negocio:** Actúa como el coordinador central de la transacción distribuida (**Patrón Saga Orquestada**). Recibe la solicitud de creación de envío, dirige la ejecución secuencial sobre los tres dominios, persiste el progreso paso a paso y ejecuta compensaciones en orden inverso ante fallos o caídas de red.

#### Evolución y Decisiones Técnicas Clave
1. **Máquina de Estados Persistente (`EstadoDespacho` en `despachos_saga`):**
   - Evolucionó desde un flujo lineal básico hacia una bitácora transaccional granular que confirma en PostgreSQL cada transición antes de ejecutar la siguiente llamada remota:
     `INICIADA` $\rightarrow$ `INVENTARIO_RESERVADO` $\rightarrow$ `TRANSPORTE_AGENDADO` $\rightarrow$ `TRACKING_GENERADO` $\rightarrow$ `COMPLETADA`.
   - Excepción: si el fallo del transportista es `CallNotPermittedException` (circuito abierto) o `BulkheadFullException`, la petición nunca salió y no se invoca `cancelar`, de modo que el *fail-fast* no se retrasa con reintentos.
   - Ante fallos, transita a `COMPENSANDO` y distingue explícitamente entre `COMPENSADA` (rollback limpio en todos los pasos confirmados) y `FALLIDA` (rollback incompleto que requiere auditoría manual).
2. **Compensación Preventiva en Orden Inverso:**
   - Las banderas de ejecución se marcan antes de invocar cada microservicio remoto para cubrir escenarios de *Read Timeout* (donde el servicio destino sí persistió el cambio pero la respuesta no llegó a tiempo). Apoyándose en la idempotencia y en la cancelación preventiva de los tres servicios de dominio (si no existe el registro, la compensación deja una marca `CANCELADO`/`LIBERADA`/`ANULADO` que rechaza con `409` un comando tardío), el orquestador revierte en orden estricto: `Tracking` $\rightarrow$ `Transporte` $\rightarrow$ `Inventario`.
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
| `POST` | `/api/despachos` | **Orquestación Saga:** Ejecuta la creación del envío coordinando `inventario`, `transportista` y `tracking` con compensación automática. | `200 OK` (Completada / Idempotente)<br>`400 Bad Request` (Solicitud inválida)<br>`409 Conflict` (Transacción ya fallida/compensada o en curso, o rechazo de negocio del inventario como stock insuficiente, tras compensar)<br>`502 Bad Gateway` (Fallo en paso de Saga + compensación)<br>`503 Service Unavailable` (Transportista no disponible / Circuito abierto) |
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
| `GET` | `/api/inventory/reservations/{transaccionId}` | **Lectura (BFF):** Devuelve producto, cantidad y estado (`RESERVADA` / `LIBERADA`) de la reserva. | `200 OK`<br>`404 Not Found` |

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
| `GET` | `/api/shipping/pickups/{transaccionId}` | **Lectura (BFF):** Devuelve estado y dirección del retiro. Sujeto al mismo caos que `/schedule` (dependencia frágil). | `200 OK`<br>`404 Not Found`<br>`503 Service Unavailable` (Caos activo) |
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
| `GET` | `/api/tracking/by-transaction/{transaccionId}` | **Lectura (BFF):** Devuelve `trackingNumber` y estado. | `200 OK`<br>`404 Not Found` |
| `POST` | `/api/tracking/chaos` | **Pruebas de Resiliencia (vía Gateway):** Activa o desactiva la falla inducida (`fail=true\|false`). | `200 OK` (`{"fallarGenerar": ...}`) |
| `GET` | `/api/tracking/chaos` | **Pruebas de Resiliencia (vía Gateway):** Consulta el estado actual del interruptor de falla. | `200 OK` (`{"fallarGenerar": ...}`) |

---

### 6. BFF Web (`bff`)
- **Estado:** Implementado.
- **Tecnología:** Spring Boot + Resilience4j. Sin base de datos ni lógica de negocio; solo agrega lecturas.
- **Rol:** Backend For Frontend del cliente web. `GET /api/bff/envios/{transaccionId}` compone en **una sola respuesta** datos de 4 servicios (`orquestador`, `inventario`, `transportista`, `tracking`).

#### Decisiones Técnicas Clave
1. **Fuente principal vs. secundarias:** el orquestador es obligatorio (si no responde, `503`; si el envío no existe, `404`). Inventario, transportista y tracking son secundarios.
2. **Resiliencia por dependencia:** timeouts (`connect=500ms`, `read=1500ms`), Circuit Breaker y Bulkhead (`transportista`=3 concurrentes, `inventario` y `tracking`=10) con fallback a `{"disponible": false, "motivo": ...}`. La respuesta marca `"degradado": true` en vez de caerse.
3. **Un `404` de un servicio secundario no es falla:** se informa `{"disponible": true, "encontrado": false}` (por ejemplo, tracking tras una compensación) y no abre el circuito.
4. **Observabilidad:** `/bff-actuator/circuitbreakers`, `/bff-actuator/bulkheads`, `/bff-actuator/circuitbreakerevents`, `/bff-actuator/bulkheadevents` (vía gateway, con token).

---

## Ejecución y Validación Rápida (Vía API Gateway)

Requisitos del host: Docker (con Compose y BuildKit) y `bash` + `curl`. Para desarrollar fuera de Docker: JDK 21 y Gradle 8.14+ (`gradle :<modulo>:bootRun`). `python3` es opcional (solo da formato al JSON).

1. **Levantar la infraestructura completa:**
   ```bash
   docker compose up -d --build
   ```
2. **Token y cabeceras:** toda petición (incluido `/actuator/**`) exige `Authorization: Bearer forma-l-secret-token`. El rate limit es de 30 peticiones por minuto por `X-Client-Id`.
3. **Crear un envío (camino feliz):**
   ```bash
   curl -s -X POST http://localhost:8080/api/despachos \
     -H "Authorization: Bearer forma-l-secret-token" -H "Content-Type: application/json" \
     -d '{"transaccionId":"TX-1","productoId":"PROD-001","cantidad":1,"direccion":"Av. Libertador 1234"}'
   ```
4. **Vista agregada del BFF:**
   ```bash
   curl -s http://localhost:8080/api/bff/envios/TX-1 -H "Authorization: Bearer forma-l-secret-token"
   ```

---

## Escenarios de Falla Reproducibles (`scripts/`)

Cada script usa solo `curl`, imprime `PASS` / `FAIL` por cada verificación y guarda la salida completa en `evidence/<escenario>-<id>.txt` (listo para capturas del informe). Se ejecutan desde la raíz del repositorio. Usan `transaccionId` únicos por ejecución, así que se pueden repetir.

| Script | Escenario | Qué demuestra |
| :--- | :--- | :--- |
| `scripts/00-reset.sh` | Reset | Apaga las fallas inducidas y muestra el estado del circuito. |
| `scripts/01-exito.sh` | 1. Éxito | Saga `COMPLETADA`, tres pasos firmes en la vista BFF, idempotencia al repetir el `transaccionId`. |
| `scripts/02-compensacion.sh` | 2. Fallo con compensación | Falla el último paso (tracking). Saga `COMPENSADA`, reserva `LIBERADA`, retiro `CANCELADO`, tracking `ANULADO` (ninguno activo). Logs con el orden inverso Tracking → Transporte → Inventario. |
| `scripts/03-cascada.sh` | 3. Falla en cascada contenida | Ver detalle abajo. |
| `scripts/04-rate-limit.sh` | Rate limiting | 40 peticiones de un cliente → `429` pasado el límite; otro cliente no se afecta; sin token → `401`. |

### Detalle del escenario 3 (`03-cascada.sh`)
1. **Antes:** circuito `CLOSED`, bulkhead con 3 cupos libres (snapshot de métricas).
2. **Lentitud (`delayMs=1500`):** las primeras llamadas tardan ~1.5 s y se cuentan como lentas; el circuito pasa a `OPEN` y las siguientes responden en milisegundos con `503` y `CallNotPermittedException` (*fail-fast* + fallback). El orquestador compensa el inventario sin esperar.
3. **Caída (`down=true`):** tras `15 s` el circuito pasa a `HALF_OPEN`, las 2 sondas fallan y vuelve a `OPEN`.
4. **Recuperación:** el transportista vuelve, las sondas pasan y el circuito queda `CLOSED`.
5. **Pico + lentitud:** 8 peticiones concurrentes con el transportista lento. El Bulkhead (3 concurrentes, sin cola) deja pasar 3 y rechaza el resto con `BulkheadFullException`; mientras tanto `GET /api/despachos/{id}` responde en milisegundos y el BFF responde con `"degradado": true` en vez de agotar hilos.

**Qué habría pasado sin el patrón:** sin timeout cada petición bloquearía un hilo hasta que el transportista respondiera; sin Circuit Breaker se seguiría golpeando al servicio caído y cada saga pagaría la espera completa; sin Bulkhead, el pico concurrente acapararía los hilos del orquestador y degradaría también las consultas que no usan al transportista (falla en cascada).

**Evidencia manual adicional (vía gateway, con token):**
```bash
H="Authorization: Bearer forma-l-secret-token"
curl -s -H "$H" localhost:8080/actuator/circuitbreakers
curl -s -H "$H" localhost:8080/actuator/circuitbreakerevents/transportista
curl -s -H "$H" localhost:8080/actuator/bulkheadevents/transportista
curl -s -H "$H" "localhost:8080/actuator/metrics/resilience4j.circuitbreaker.not.permitted.calls?tag=name:transportista"
```
Para los logs de la saga: `docker compose logs orquestador | grep "SAGA <transaccionId>"`.

### Por qué Saga y no Two-Phase Commit
El transportista es un servicio externo, lento y que se cae. 2PC exigiría mantener bloqueos distribuidos y un coordinador a la espera de todos los participantes (un participante lento o caído bloquea a los demás), y un sistema externo no expone un protocolo de *prepare/commit*. La Saga confirma cada paso localmente y compensa en orden inverso, aceptando consistencia eventual.

### Por qué orquestación y no coreografía
El flujo es secuencial y lineal (3 pasos). Un orquestador centraliza el estado (`despachos_saga`), el orden de las compensaciones y el manejo de la dependencia frágil (Circuit Breaker + Bulkhead en un solo punto), y hace trazable cada transición. En coreografía el estado y la lógica de compensación quedarían repartidos entre los servicios.
