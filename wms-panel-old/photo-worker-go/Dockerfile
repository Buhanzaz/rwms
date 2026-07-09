FROM golang:1.25-bookworm AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends libvips-dev pkg-config build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY go.mod ./
COPY main.go ./
RUN go mod download
RUN CGO_ENABLED=1 GOOS=linux go build -mod=mod -o /out/photo-worker .

FROM golang:1.25-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends libvips42 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /out/photo-worker /app/photo-worker

ENTRYPOINT ["/app/photo-worker"]
