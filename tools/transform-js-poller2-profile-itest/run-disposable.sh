#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$root/../.." && pwd)
runtime=$(mktemp -d /tmp/poller2-transform-js-runtime.XXXXXX)
container=poller2-transform-js-experiment
mock_pid=

cleanup() {
  status=$?
  if [[ ${KEEP_RUNTIME:-0} == 1 ]]; then
    echo "Preserved live runtime at $runtime; container name: $container" >&2
    return
  fi
  docker rm -f "$container" >/dev/null 2>&1 || true
  if [[ -n "$mock_pid" ]]; then
    kill "$mock_pid" >/dev/null 2>&1 || true
  fi
  if [[ $status -ne 0 ]]; then
    echo "Preserved failed runtime at $runtime" >&2
    return
  fi
  docker run --rm --entrypoint rm -v "$runtime:/data" openhab/openhab:5.3.0-snapshot-alpine -rf /data >/dev/null \
    2>&1 || true
  rmdir "$runtime" >/dev/null 2>&1 || true
}
trap cleanup EXIT

modbus_jar="$repo/bundles/org.openhab.binding.modbus/target/org.openhab.binding.modbus-5.3.0-SNAPSHOT.jar"
[[ -f "$modbus_jar" ]] || { echo "Build the Modbus binding first: $modbus_jar is missing" >&2; exit 1; }
transport_jar="${OPENHAB_CORE:-$HOME/openhab-core}/bundles/org.openhab.core.io.transport.modbus/target/org.openhab.core.io.transport.modbus-5.3.0-SNAPSHOT.jar"
[[ -f "$transport_jar" ]] || { echo "Build the Core Modbus transport first: $transport_jar is missing" >&2; exit 1; }

mkdir -p "$runtime/conf/yaml" "$runtime/conf/services" "$runtime/userdata" "$runtime/addons"
cp "$root/conf/yaml/poller2-transform-js.yaml" "$runtime/conf/yaml/"
cp "$root/conf/services/addons.cfg" "$runtime/conf/services/"
cp "$modbus_jar" "$runtime/addons/"
cp "$transport_jar" "$runtime/addons/"
python3 "$root/mock_modbus_tcp.py" >"$runtime/mock-modbus.log" 2>&1 &
mock_pid=$!

docker run -d --rm --name "$container" --add-host=host.docker.internal:host-gateway \
  -p 18080:8080 \
  -e OPENHAB_HTTP_PORT=8080 \
  -e EXTRA_JAVA_OPTS='-Dorg.openhab.model.yaml.enabled=true' \
  -v "$runtime/conf:/openhab/conf" \
  -v "$runtime/userdata:/openhab/userdata" \
  -v "$runtime/addons:/openhab/addons" \
  openhab/openhab:5.3.0-snapshot-alpine >/dev/null

for _ in $(seq 1 90); do
  state=$(curl --silent --show-error --fail http://127.0.0.1:18080/rest/items/ProfileExperimentPower/state || true)
  if [[ "$state" == "4.2 W" ]]; then
    echo "PASS: poller2 raw register 42 -> transform:JS -> Number:Power Item state $state"
    exit 0
  fi
  sleep 2
done

echo "FAIL: expected ProfileExperimentPower state '4.2 W', got '${state:-<unavailable>}'" >&2
docker logs "$container" >&2
exit 1
