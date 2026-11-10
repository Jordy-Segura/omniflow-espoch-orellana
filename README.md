# OmniFlow

Sistema de asistencia automática y monitoreo inteligente de los laboratorios de cómputo de la
**ESPOCH Sede Orellana**, mediante visión computacional, una aplicación móvil Android e integración
con el sistema académico OASIS.

Proyecto Integrador de la carrera de Tecnologías de la Información. Integra las asignaturas
Escalabilidad de Redes, Interoperabilidad de Plataformas, Computación Móvil y Machine Learning.

## Estructura del repositorio

| Carpeta | Contenido |
|---|---|
| [`OmniFlowApp/`](OmniFlowApp/) | Aplicación Android (Kotlin, Jetpack Compose, MVVM): monitoreo con cámara, conteo de personas, asistencia por asignatura, registro facial y actualización automática. |
| [`omniflow-backend/`](omniflow-backend/) | Servidor FastAPI: API REST y WebSocket, sincronización con OASIS (SOAP), reconocimiento facial para la asistencia, lectura del PDF de matrícula y publicación de versiones de la app. |
| [`omniflow-ml/`](omniflow-ml/) | Componente de Machine Learning: notebook con análisis exploratorio, preprocesamiento, comparación de modelos y calibración del umbral de reconocimiento facial. |

## Puesta en marcha rápida

Servidor (requiere Python 3.12):

```bash
cd omniflow-backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
cp .env.example .env   # completar los valores
.venv/Scripts/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Aplicación: abrir `OmniFlowApp/` en Android Studio, definir `OMNIFLOW_API_URL` en `local.properties`
y ejecutar el módulo `app`. Los detalles están en el README de cada carpeta.

## Datos y seguridad

- No se suben al repositorio las claves (`.env`, `local.properties`, almacén de firma), las bases de datos
  ni las APK publicadas.
- Los datos de docentes y estudiantes se obtienen de OASIS en el servidor; no se versionan.
- Los rostros solo se registran con consentimiento y se almacena únicamente su vector de características
  (Ley Orgánica de Protección de Datos Personales).
