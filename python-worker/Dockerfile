FROM python:3.11-slim AS worker-runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

ENV GDAL_CACHEMAX=128 GDAL_NUM_THREADS=1 OPJ_NUM_THREADS=1
ENV OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        gdal-bin \
        libgdal-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

CMD ["python", "main.py"]

FROM worker-runtime AS tinycd-worker
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu \
    torch==2.12.0+cpu torchvision==0.27.0+cpu

FROM worker-runtime AS worker
