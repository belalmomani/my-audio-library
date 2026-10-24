FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /srv
COPY requirements.txt requirements-ml.txt ./
ARG WITH_ML=false
RUN pip install --no-cache-dir -r requirements.txt && \
    if [ "$WITH_ML" = "true" ]; then pip install --no-cache-dir -r requirements-ml.txt; fi
COPY app ./app
RUN useradd -m app && mkdir -p /data && chown app /data
USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
