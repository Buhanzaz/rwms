#!/usr/bin/env bash

set -o errexit -o nounset -o pipefail

readonly RWMS_VALHALLA_CONFIG_FILE="/custom_files/valhalla.json"
readonly RWMS_MAX_TIME_CONTOUR_MINUTES=240
readonly RWMS_SOURCE_MANIFEST="/osm-source/rwms-pbf-manifest.sha256"
readonly RWMS_APPLIED_SOURCE_MANIFEST="/custom_files/rwms-pbf-manifest.sha256"

if ! test -s "${RWMS_SOURCE_MANIFEST}"; then
  echo "ERROR: RWMS routing source manifest is missing." >&2
  exit 1
fi

rwms_sources_changed="False"
if ! cmp --silent "${RWMS_SOURCE_MANIFEST}" "${RWMS_APPLIED_SOURCE_MANIFEST}"; then
  rwms_sources_changed="True"
  export force_rebuild="True"
  export build_admins="Force"
fi

# Let the upstream image create/update its complete runtime configuration and
# tiles, but stop it before the service process is started. This keeps the
# generated paths and version-specific defaults owned by the image.
serve_tiles=False /valhalla/scripts/docker-entrypoint.sh build_tiles

if [[ "${rwms_sources_changed}" == "True" ]]; then
  cp "${RWMS_SOURCE_MANIFEST}" "${RWMS_APPLIED_SOURCE_MANIFEST}"
fi

jq \
  --argjson maximum_minutes "${RWMS_MAX_TIME_CONTOUR_MINUTES}" \
  '.service_limits.isochrone.max_contours = 4
   | .service_limits.isochrone.max_time_contour = $maximum_minutes' \
  "${RWMS_VALHALLA_CONFIG_FILE}" \
  | sponge "${RWMS_VALHALLA_CONFIG_FILE}"

jq --exit-status \
  --argjson maximum_minutes "${RWMS_MAX_TIME_CONTOUR_MINUTES}" \
  '.service_limits.isochrone.max_contours == 4
   and .service_limits.isochrone.max_time_contour == $maximum_minutes' \
  "${RWMS_VALHALLA_CONFIG_FILE}" >/dev/null

exec valhalla_service \
  "${RWMS_VALHALLA_CONFIG_FILE}" \
  "${server_threads:-2}"
