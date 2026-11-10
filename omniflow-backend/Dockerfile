# API OmniFlow. Construir sin vision:  docker build --build-arg INSTALL_VISION=false -t omniflow-api .
FROM python:3.12-slim

ARG INSTALL_VISION=true
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    YOLO_CONFIG_DIR=/app/.ultralytics

WORKDIR /app

# libgl/glib: OpenCV (ultralytics trae opencv-python como dependencia)
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt requirements-vision.txt ./
RUN pip install -r requirements.txt
RUN if [ "$INSTALL_VISION" = "true" ]; then \
      pip install --index-url https://download.pytorch.org/whl/cpu torch torchvision \
      && pip install -r requirements-vision.txt \
      && python -c "from ultralytics import YOLO; YOLO('yolov8n.pt')" ; \
    fi

COPY app ./app

RUN useradd --create-home --uid 1000 omniflow && mkdir -p /data/releases && chown -R omniflow /app /data
USER omniflow

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8000/api/v1/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips=*"]
