FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive
ENV VIRTUAL_ENV=/opt/rs-python
ENV PATH="${VIRTUAL_ENV}/bin:${PATH}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        gdal-bin \
        libgdal-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /workspace/target/*.jar /app/app.jar
COPY python-worker/requirements.txt /app/python-worker/requirements.txt

RUN python3 -m venv "${VIRTUAL_ENV}" \
    && pip install --no-cache-dir -r /app/python-worker/requirements.txt

COPY python-worker/scripts /app/python-worker/scripts

RUN mkdir -p /app/data/geoserver-raster /tmp/rs-upload

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
