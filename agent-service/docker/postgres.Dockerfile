FROM postgis/postgis:16-3.4 AS builder
ARG PGVECTOR_VERSION=0.8.6
ARG PGVECTOR_SHA256=10bf9938906e5d643bbc4a7eea104b6f57ba4898e5b76b20e60484ea1d5a7f8f
# This existing PostGIS tag is Bullseye; PGDG moved its EOL distribution to the official archive.
RUN sed -i 's|http://apt.postgresql.org/pub/repos/apt|https://apt-archive.postgresql.org/pub/repos/apt|g' /etc/apt/sources.list.d/pgdg.list \
    && sed -i 's|http://deb.debian.org/debian-security|https://archive.debian.org/debian-security|g; s|http://deb.debian.org/debian|https://archive.debian.org/debian|g' /etc/apt/sources.list \
    && apt-get -o Acquire::Check-Valid-Until=false update && apt-get install -y --no-install-recommends ca-certificates curl build-essential postgresql-server-dev-16 \
    && rm -rf /var/lib/apt/lists/*
RUN curl --fail --location --proto '=https' --tlsv1.2 "https://github.com/pgvector/pgvector/archive/refs/tags/v${PGVECTOR_VERSION}.tar.gz" -o /tmp/vector.tar.gz \
    && echo "${PGVECTOR_SHA256}  /tmp/vector.tar.gz" | sha256sum -c - \
    && mkdir /tmp/vector && tar -xzf /tmp/vector.tar.gz --strip-components=1 -C /tmp/vector \
    && make -C /tmp/vector OPTFLAGS="" with_llvm=no && make -C /tmp/vector install with_llvm=no

FROM postgis/postgis:16-3.4
COPY --from=builder /usr/lib/postgresql/16/lib/vector.so /usr/lib/postgresql/16/lib/vector.so
COPY --from=builder /usr/share/postgresql/16/extension/vector* /usr/share/postgresql/16/extension/
