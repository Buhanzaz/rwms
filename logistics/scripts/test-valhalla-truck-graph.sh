#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
fixture="$project_dir/backend/tests/fixtures/valhalla_maxlength.osm"
runtime_dir=$(mktemp -d /tmp/rwms-valhalla-synthetic.XXXXXX)
container_name="logistics-valhalla-synthetic-$$"

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  case "$runtime_dir" in
    /tmp/rwms-valhalla-synthetic.*) rm -rf -- "$runtime_dir" ;;
  esac
}
trap cleanup EXIT INT TERM

cp "$fixture" "$runtime_dir/network.osm"
docker run --rm \
  -v "$runtime_dir:/work" \
  debian:bookworm-slim \
  sh -ec 'apt-get update -qq && apt-get install -y -qq osmium-tool >/dev/null && osmium cat /work/network.osm -o /work/network.osm.pbf --overwrite'

cd "$project_dir"
docker compose build backend-test >/dev/null
docker compose run --rm --no-deps backend-test true >/dev/null

docker run -d \
  --name "$container_name" \
  -v "$runtime_dir:/custom_files" \
  -e build_elevation=False \
  -e build_admins=False \
  -e build_time_zones=False \
  -e build_tar=False \
  -e force_rebuild=True \
  -e use_tiles_ignore_pbf=False \
  -e serve_tiles=True \
  -e server_threads=1 \
  ghcr.io/valhalla/valhalla-scripted:3.8.3 >/dev/null
docker network connect logistics-simulator_default "$container_name"

attempt=0
until docker exec "$container_name" \
  curl --fail --silent --show-error http://127.0.0.1:8002/status >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 120 ]; then
    docker logs --tail 120 "$container_name"
    exit 1
  fi
  sleep 1
done

docker compose run --rm --no-deps \
  -e "VALHALLA_SYNTHETIC_URL=http://$container_name:8002" \
  backend-test \
  pytest -q tests/test_valhalla_tagged_graph_integration.py
