# OmniFlow App (Android · Kotlin)

Sistema inteligente de asistencia, monitoreo y analítica de laboratorios de la ESPOCH mediante visión computacional.
Abre esta carpeta en **Android Studio** (AGP 9.3, Kotlin 2.3, compileSdk 37, minSdk 26).

## Qué hace esta versión (0.1)

| Módulo | Estado |
|---|---|
| Inicio de sesión con cuentas propias del servidor (correo o cédula) + modo demostración sin cuenta | ✅ |
| Los 4 laboratorios de cómputo con su horario oficial del período (PDF) y docentes/inscritos de OASIS | ✅ |
| Actualización automática desde el backend (sin reinstalar la APK a mano) | ✅ |
| Dashboard, laboratorios, detalle, horario semanal por días, alertas, reportes, perfil | ✅ |
| Detalle de cada materia: docente, datos académicos, estudiantes inscritos y su asistencia acumulada | ✅ |
| Detalle de cada alerta (qué significa, qué hacer, clase en ese momento) y sonido propio de alerta | ✅ |
| Asistencia por clase: en curso, próximas y finalizadas, con resumen del día | ✅ |
| Modo demostración: agregar personas a una clase y tomar asistencia sin cuenta (solo en el teléfono) | ✅ |
| Administración (administrador y coordinador): docentes con usuario, contraseña y materias; laboratorios y horario | ✅ |
| **Cámara del teléfono** como cámara del laboratorio (trasera o frontal) | ✅ |
| **Otro teléfono por Wi-Fi** como cámara IP temporal (IP Webcam / DroidCam, MJPEG o JPG) | ✅ |
| Detección de personas on-device (CNN EfficientDet-Lite0, COCO, MediaPipe/TFLite) | ✅ |
| Seguimiento multi-persona **ByteTrack** (filtro de Kalman + algoritmo Húngaro) | ✅ |
| Conteo de entradas/salidas por línea virtual | ✅ |
| Predicción de ocupación (regresión lineal OLS + suavizado de Holt) | ✅ |
| Detección de anomalías (z-score robusto mediana/MAD por franja horaria) | ✅ |
| Reglas de negocio: sobreaforo, uso fuera de horario, ausencia docente, salida anticipada, atrasos | ✅ |
| Notificaciones Android, Room (SQLite), Hilt, DataStore | ✅ |
| Sincronización con el backend FastAPI (`../omniflow-backend`, `/api/v1/occupancy-events/batch`) | ✅ |

## Algoritmos de Machine Learning

```
frame ─► EfficientDet-Lite0 (CNN) ─► ByteTrack ─► mediana móvil ─► Room
            detección "person"        Kalman 8D +      (anti-parpadeo)   │
                                      Húngaro, 2 etapas                   ├─► Reglas de negocio ─► Alertas
                                      (alta/baja conf.)                   ├─► Anomalías (z robusto)
                                                                          └─► Pronóstico 15 min (OLS + Holt)
```

- `vision/detector/MediaPipePersonDetector.kt` – red neuronal convolucional ejecutada en el teléfono.
- `vision/tracking/ByteTracker.kt`, `KalmanBoxFilter.kt`, `Hungarian.kt` – seguimiento (paper ByteTrack, ECCV 2022).
- `vision/tracking/LineCrossingCounter.kt` – entradas/salidas con histéresis.
- `analytics/OccupancyForecaster.kt` – regresión lineal por mínimos cuadrados + Holt.
- `analytics/AnomalyDetector.kt` – z-score robusto (Iglewicz & Hoaglin, umbral 3.5).
- `domain/usecase/LabRules.kt` – estado del laboratorio y reglas del Documento Maestro.

Pruebas unitarias (14) en `app/src/test`: `./gradlew testDebugUnitTest`. Con el backend corriendo,
`OMNIFLOW_TEST_API=http://127.0.0.1:8000/ ./gradlew testDebugUnitTest` prueba además el cliente Retrofit real contra él.

## Usar otro teléfono como cámara (hasta tener cámaras IP)

1. En el teléfono-cámara instala **IP Webcam** (o DroidCam) y pulsa *Iniciar servidor*.
2. Ambos teléfonos en la misma red Wi-Fi.
3. En OmniFlow: **Perfil → Ajustes de cámara e IA → Otro teléfono / cámara IP**, escribe
   `http://IP:8080/video` (stream) o `http://IP:8080/shot.jpg` (fotos) y pulsa *Probar conexión*.
4. **Cámara → elige el laboratorio → Iniciar monitoreo.**

Cuando llegue la cámara IP real basta con cambiar la URL por la MJPEG de la cámara
(las cámaras solo RTSP se procesarán en el backend con OpenCV).

También puedes usar la cámara del mismo teléfono: déjalo fijo apuntando al laboratorio (la pantalla se mantiene encendida).

## Laboratorios y horario

**Asistencia por estudiante:** pestaña *Asistencia* (docentes/coordinación): clases del día → lista de la asignatura
con quiénes están y quiénes faltan (se actualiza sola durante la clase) y corrección manual. Estudiantes: *Mi
asistencia* y *Registrar mi rostro* (con consentimiento). La cámara del laboratorio (pestaña *Cámara* → *Asistencia
automática por rostro*) envía fotogramas al servidor, que reconoce a los inscritos. Requiere el backend.


La app trae el horario oficial del período P0046 (7 sep 2026 – 5 feb 2027) de **Laboratorio de Cómputo 1, 2, 3 y 4**
(`app/src/main/assets/lab_schedule.json`: 57 bloques del PDF con el docente y el número de inscritos que da OASIS).
Si hay backend configurado, al abrirse descarga la versión actualizada (`GET /api/v1/schedule`), que el servidor
mantiene sincronizada con OASIS.

## Actualizaciones automáticas (sin reinstalar la APK)

1. **Una sola vez** instala en el teléfono una versión publicada (descárgala desde
   `http://SERVIDOR:8000/api/v1/app/download/...` o con `adb install`). Los "Run" de Android Studio son
   versiones de desarrollo (versionCode 1) y no se auto-actualizan.
2. Cada vez que cambies algo ejecuta en la PC:
   ```
   publicar-actualizacion.bat "Qué cambió"
   ```
   Compila, firma con `keystore/omniflow.jks` y sube la APK al backend (`OMNIFLOW_PUBLISH_URL`, `RELEASE_UPLOAD_KEY`
   en `local.properties`; la misma clave va en el `.env` del backend).
3. Los teléfonos revisan al abrir la app y cada 6 h: descargan la nueva versión, verifican su SHA-256 y la instalan.
   - La primera vez Android pide permitir que OmniFlow "instale apps" (botón **Dar permiso** en Inicio).
   - En teléfonos con **Google Play Protect** activo, cada actualización de una app que no viene de Play Store
     muestra una pantalla de Play Protect (**Escanear app** o **Instalar sin escanear**). Es una política de
     Google, no de la app. En el teléfono dedicado a cámara se puede desactivar "Analizar apps con Play Protect";
     la alternativa sin avisos es publicar en Google Play (prueba interna).
   - Se puede desactivar en *Perfil → Actualizaciones*.

**Respalda `keystore/omniflow.jks` y la contraseña de `local.properties`**: si se pierden, los teléfonos tendrán
que desinstalar la app una vez porque Android solo instala actualizaciones firmadas con la misma clave.

## Credenciales de OASIS

Los servicios de `swoasis.espoch.edu.ec/OASis/OAS_Interop/*.asmx` responden **sin** usuario de servicio
(la cabecera `<credentials>` va vacía), así que el login institucional funciona
directamente. Si la DTIC entrega un usuario de servicio, agrégalo en `local.properties` (no se sube a git):

```properties
OASIS_WS_USER=usuario_servicio
OASIS_WS_PASSWORD=clave_servicio
# Opcional: backend FastAPI (Cloudflare Tunnel)
OMNIFLOW_API_URL=https://omniflow.tudominio.com
```

El **modo demostración** (enlace bajo el botón Ingresar) permite entrar con un rol sin cuenta. La app no genera
datos simulados: los reportes muestran solo lo que registran las cámaras. Si quedaron datos de prueba de una
versión anterior, se borran en Ajustes.

## Estructura (MVVM + Clean Architecture)

```
ec.edu.espoch.omniflow
├── ui/screens        login, dashboard, labs, monitor, alerts, reports, profile, settings
├── ui/components     tarjetas, chips de estado, gráficos
├── navigation        NavHost + barra inferior por rol
├── domain/model      Lab, ScheduleSlot, LabSnapshot, Alert, User…
├── domain/usecase    LabRules
├── data/local        Room (labs, schedule, occupancy_events, class_sessions, alerts)
├── data/remote       oasis (SOAP), api (Retrofit → FastAPI)
├── data/repository   Auth, Lab, Alert, Report, OccupancyRecorder, Sync, Settings
├── vision            detector, tracking, camera (CameraX + MJPEG)
├── analytics         pronóstico, anomalías, suavizado
├── di                Hilt
└── utils             notificaciones
```
