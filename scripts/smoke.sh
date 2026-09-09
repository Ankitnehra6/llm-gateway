#!/usr/bin/env bash
#
# Sends real requests through a running gateway and shows what came back.
# A quick end-to-end check that auth, routing, budgets and accounting work together.

set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"

echo "==> Checking the gateway is up"
if ! curl -fsS "${GATEWAY}/actuator/health" >/dev/null 2>&1; then
  echo "gateway is not answering at ${GATEWAY}; run 'make up' first" >&2
  exit 1
fi
echo "    ok"
echo

echo "==> A completion on the pro tier"
curl -sS -X POST "${GATEWAY}/v1/chat/completions" \
  -H 'Authorization: Bearer demo-key-pro' \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello gateway"}]}' \
  | python3 -m json.tool
echo

echo "==> Current usage for that tenant"
curl -sS "${GATEWAY}/v1/usage" -H 'Authorization: Bearer demo-key-pro' | python3 -m json.tool
echo

echo "==> An unknown key is rejected"
curl -s -o /dev/null -w '    status: %{http_code}\n' \
  -X POST "${GATEWAY}/v1/chat/completions" \
  -H 'Authorization: Bearer nope' \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
echo

echo "==> An invalid role is rejected with problem details"
curl -sS -X POST "${GATEWAY}/v1/chat/completions" \
  -H 'Authorization: Bearer demo-key-pro' \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"wizard","content":"hi"}]}' \
  | python3 -m json.tool
echo

echo "==> Exhausting the free tier's 5000-token budget"
for i in $(seq 1 10); do
  status=$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST "${GATEWAY}/v1/chat/completions" \
    -H 'Authorization: Bearer demo-key-free' \
    -H 'Content-Type: application/json' \
    -d "{\"messages\":[{\"role\":\"user\",\"content\":\"$(head -c 4000 < /dev/zero | tr '\0' 'x')\"}]}")
  printf '%s ' "${status}"
done
echo
echo "    (200 until the budget runs out, then 429)"
