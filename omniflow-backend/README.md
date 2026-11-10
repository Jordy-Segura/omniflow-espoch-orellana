# OmniFlow API (backend FastAPI)

Backend de **OmniFlow App** (ESPOCH): recibe la ocupación detectada por los teléfonos, procesa cámaras IP con
**YOLOv8 + ByteTrack + OpenCV**, aplica las reglas de negocio, genera alertas en tiempo real y reportes,
y se integra con **OASIS** (SOAP).

```
App Android ──HTTPS/JSON──►  API OmniFlow (FastAPI) ──SOAP──► OASIS (Seguridad, InfoGeneral, InfoCarrera)
Cámaras IP  ──RTSP/MJPEG──►  módulo de visión (YOLOv8 + ByteTrack)
                                   │
                                   ├──► PostgreSQL (eventos, sesiones de clase, alertas, auditoría)
                                   └──► WebSocket /ws/live (ocupación y alertas en tiempo real)
```

## Arranque rápido con Docker

```bash
cp .env.example .env        # completa JWT_SECRET y POSTGRES_PASSWORD
docker compose up -d --build
```

- API: http://localhost:8000 · Documentación interactiva: http://localhost:8000/docs
- Sin visión (imagen más liviana, ~200 MB en vez de ~2 GB): `INSTALL_VISION=false docker compose up -d --build`
- Con acceso remoto por Cloudflare Tunnel: pon `CLOUDFLARE_TUNNEL_TOKEN` en `.env` y
  `docker compose --profile tunnel up -d`

## Arranque sin Docker (desarrollo, SQLite)

```bash
python -m venv .venv
.venv\Scripts\activate                 # Windows  (Linux/Mac: source .venv/bin/activate)
pip install -r requirements-dev.txt
pip install -r requirements-vision.txt # opcional: YOLOv8 + OpenCV
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Pruebas: `pytest` (42 pruebas; la de YOLOv8 real se omite si no instalaste `requirements-vision.txt`).

## Laboratorios, horario, docentes y estudiantes

> **Asistencia automática por estudiante.** La lista de cada asignatura sale de OASIS; la cámara del laboratorio envía
> un fotograma cada 8 s a `POST /api/v1/attendance/frame?labCode=…`, el servidor detecta rostros (YuNet), extrae
> embeddings (SFace) y los compara solo con los estudiantes inscritos en la clase en curso (1-NN coseno con umbral y
> margen calibrados en `omniflow-ml/`). Reconocido → PRESENTE (o ATRASO después de 10 min); al terminar la clase,
> sin registro → AUSENTE. El docente corrige con `PUT /api/v1/attendance/slot/{id}/students/{cedula}`.
> Registro facial con consentimiento: `POST /api/v1/faces/enroll` (solo se guardan vectores) y `DELETE /api/v1/faces`.
>
> **Cuentas propias (como assistant-grades):** al sincronizar OASIS se crea una cuenta por docente y estudiante
> (usuario = correo institucional o cédula, clave inicial = cédula sin guion, cambio obligatorio al ingresar).
> OASIS solo se usa para el login si hay credenciales de servicio. Roles extra: `ADMIN_USERS`, `COORDINATOR_USERS`.

- **Laboratorios y horario**: los 4 laboratorios de cómputo y sus 57 bloques salen del PDF oficial del período
  (`app/data/horario_laboratorios_P0046.json`, transcrito de *Anexo 3_Horario_Laboratorio_Computacion_sep26-feb27_v7.pdf*).
  Carreras: TI (Tecnologías de la Información), IAMB (Ing. Ambiental), AGR (Agronomía) y ZOO (Zootecnia),
  según el prefijo del grupo del PDF (TI03, AMBI01, AGRO03, ZOOT05).
- **Docentes, estudiantes e inscritos**: se traen de **OASIS** al arrancar (y con `POST /api/v1/admin/oasis-sync`),
  igual que hace *assistant-grades*:
  malla de la carrera → materia de cada bloque → docente (horario del período `GetHorariosDocente`, o el del PDF
  si viene escrito) → paralelo → alumnos de esa materia (`GetAlumnosMateria`). Resultado en el período P0046:
  57/57 bloques con docente, 43 docentes, 469 estudiantes.
- OASIS **no exige** credenciales de servicio para estas consultas; si la DTIC las entrega se usan igual.
- El reporte de la última sincronización (`GET /api/v1/admin/oasis-sync`) lista por bloque el docente elegido,
  de dónde salió y las diferencias encontradas (p. ej. el PDF nombra a *Paguay Soxo Paul Xavier* en
  Machine Learning y Fundamentos de Base de Datos, pero ese docente no está en OASIS; y la mayoría de horas
  oficiales de OASIS no coinciden con las del PDF de laboratorios, que es el que manda para la ocupación).

## Actualización automática de la app

El backend guarda la última APK y la app la descarga e instala sola:

1. Pon la misma clave en `.env` (`RELEASE_UPLOAD_KEY`) y en `OmniFlowApp/local.properties`.
2. En la PC: `OmniFlowApp\publicar-actualizacion.bat "Qué cambió"` → compila y sube la APK.
3. Los teléfonos revisan al abrir la app y cada 6 h (`GET /api/v1/app/latest`), descargan, verifican el SHA-256
   e instalan.

## Conectar la app Android

En la app: **Perfil → Ajustes de cámara e IA → URL del backend OmniFlow** y pulsa *Probar*.

| Dónde corre el backend | URL en la app |
|---|---|
| Emulador de Android Studio, backend en tu PC | `http://10.0.2.2:8000` |
| Teléfono real en la misma Wi-Fi | `http://IP-de-tu-PC:8000` (abre el puerto 8000 en el firewall) |
| Cloudflare Tunnel | `https://omniflow.tudominio.com` |

La app envía sus lecturas cada minuto a `POST /api/v1/occupancy-events/batch`. El endpoint es idempotente
(reenviar el mismo lote no duplica datos) y responde `{"accepted": n, "duplicates": n, "rejected": n}`.

## Endpoints principales (`/api/v1`)

| Método | Ruta | Acceso |
|---|---|---|
| GET | `/health` | público |
| POST | `/auth/login` → JWT (OASIS, o `demoRole` en modo demo) · GET `/auth/me` | público / token |
| GET | `/labs` (contrato de la app) · `/labs/{code}` | público |
| GET | `/labs/status` estado en tiempo real (Libre/Ocupado/Próximo/Fuera de horario/Anómalo) | token |
| GET/POST/DELETE | `/labs/{code}/schedule` horario semanal | token / coordinador |
| GET | `/labs/{code}/occupancy?minutes=60` · `/labs/{code}/forecast?horizon=15` | token |
| POST | `/occupancy-events/batch` (contrato de la app) | `X-Device-Key` si se configura |
| GET/POST | `/alerts`, `/alerts/{id}/read`, `/alerts/read-all` | token |
| GET | `/reports/weekly?days=7` · `/reports/occupancy.csv` | docente/coordinador/admin |
| GET/POST/DELETE | `/cameras`, `/cameras/{id}/start`, `/stop`, `/status`, `/snapshot.jpg` | coordinador/admin |
| GET | `/schedule` horario de los 4 laboratorios con docente e inscritos (lo usa la app) | público |
| GET | `/schedule/{id}/students` lista de clase (OASIS) | docente/coordinador/admin |
| GET | `/people?role=DOCENTE\|ESTUDIANTE` | coordinador/admin |
| POST/GET | `/admin/oasis-sync` sincronizar con OASIS / ver el último reporte | coordinador/admin |
| GET | `/app/latest`, `/app/download/{archivo}` · PUT `/app/releases` (`X-Release-Key`) | público / clave |
| WS | `/ws/live?token=JWT` → `{"type":"occupancy",...}` / `{"type":"alert","alert":{...}}` | token |

Roles: `ADMINISTRADOR`, `COORDINADOR`, `DOCENTE`, `ESTUDIANTE` (mapeados desde los roles de OASIS;
`ADMIN_USERS` promueve usuarios a administrador).

## Visión computacional en el servidor

1. Registra la cámara (coordinador o administrador):
   ```bash
   curl -X POST http://localhost:8000/api/v1/cameras -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"labCode":"LAB-C1","name":"Puerta","url":"rtsp://usuario:clave@192.168.1.64:554/stream1","countingLine":true}'
   ```
   `url` admite RTSP, MJPEG por HTTP (p. ej. otro teléfono con IP Webcam: `http://IP:8080/video`),
   un archivo de video (útil para demos, se reproduce en bucle) o el índice de una webcam (`0`).
2. `POST /api/v1/cameras/{id}/start` → un hilo lee el stream con OpenCV, ejecuta **YOLOv8n** (clase *person*)
   con **ByteTrack** (`model.track(persist=True, tracker="bytetrack.yaml")`), suaviza con mediana móvil,
   cuenta entradas/salidas con la línea virtual y guarda una lectura cada `VISION_SAMPLE_SECONDS`.
3. `GET /cameras/{id}/snapshot.jpg` devuelve el último frame con cajas e IDs.

Las cámaras con `enabled=true` se reanudan solas al reiniciar el servidor.

## Lógica de negocio y analítica

Mismos algoritmos que la app (para que teléfono y servidor coincidan):

- **Reglas**: sobreaforo, laboratorio ocupado sin clase, sin presencia a los 15 min de iniciada la clase
  (posible ausencia del docente), laboratorio vacío antes de terminar (salida anticipada), atrasos ≥ 10 min.
  Enfriamiento de 10 min por laboratorio y tipo de alerta. Los lotes atrasados (> 5 min) se guardan pero no alertan.
- **Anomalías**: z-score robusto (mediana/MAD) contra el historial de la misma franja (día + hora).
- **Pronóstico**: promedio de regresión lineal (OLS) y suavizado de Holt sobre los últimos 30 min.

## OASIS

Los servicios `http://swoasis.espoch.edu.ec/OASis/OAS_Interop/{Seguridad,InfoGeneral,InfoCarrera}.asmx`
responden sin usuario de servicio (la cabecera `<credentials>` va vacía), igual que en *assistant-grades*.
Si la DTIC entrega uno, configúralo en `OASIS_WS_USER` / `OASIS_WS_PASSWORD`. Un usuario inexistente hace que
OASIS responda "Referencia a objeto no establecida…", que la API traduce a *usuario o contraseña incorrectos*.
El modo demostración (`{"username":"Ana","demoRole":"DOCENTE"}`) debe desactivarse en producción
(`DEMO_LOGIN_ENABLED=false`).

## Estructura

```
app/
├── main.py            app FastAPI, ciclo de vida (tablas, datos iniciales, cámaras)
├── config.py          variables de entorno
├── database.py        SQLAlchemy
├── data/              horario de laboratorios del período (PDF transcrito)
├── models/            tablas: labs, schedule_slots, people, slot_students, occupancy_events, class_sessions,
│                      alerts, cameras, sync_runs, audit_log
├── schemas/           Pydantic (JSON en camelCase como la app)
├── routers/           health, auth, labs, schedule, occupancy, alerts, reports, cameras, app_updates, ws
├── services/          reglas, analítica, ingesta de ocupación, estado de laboratorios, datos iniciales,
│                      sincronización con OASIS
├── repositories/      acceso a datos
├── auth/              JWT, roles, login OASIS/demo, clave de dispositivo
├── integrations/      cliente SOAP de OASIS
├── vision/            YOLOv8 + ByteTrack, hilos por cámara, línea de conteo
├── websocket/         difusión en tiempo real
└── reports/           reporte semanal y exportación CSV
tests/                 pytest
```

> Las tablas se crean al arrancar (`create_all`). Antes de producción conviene agregar migraciones con Alembic.
