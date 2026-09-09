#!/usr/bin/env bash
#
# Sends real requests through a running gateway and shows what came back.
# An end-to-end check that auth, routing, caching, budgets and accounting all
# work together.
#
# Safe to re-run: every prompt is salted with a per-run id, so a warm cache from
# a previous run cannot make the "first ask" look like a hit.

set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
RUN_ID="$(date +%s)-$$"

ask() {
  curl -sS -X POST "${GATEWAY}/v1/chat/completions" \
    -H "Authorization: Bearer ${2:-demo-key-pro}" \
    -H 'Content-Type: application/json' \
    -d "{\"messages\":[{\"role\":\"user\",\"content\":\"$1\"}]}"
}

echo "==> Checking the gateway is up"
if ! curl -fsS "${GATEWAY}/actuator/health" >/dev/null 2>&1; then
  echo "gateway is not answering at ${GATEWAY}; run 'make up' first" >&2
  exit 1
fi
echo "    ok"
echo

echo "==> A completion on the pro tier"
ask "hello gateway ${RUN_ID}" | python3 -m json.tool
echo

echo "==> Semantic cache"
ask "What is the capital of France ${RUN_ID}" | python3 -c "
import json,sys; g=json.load(sys.stdin)['gateway']
print('    cold ask       cache_hit=%-5s provider=%s' % (g['cache_hit'], g['provider']))"
ask "What is the capital of France ${RUN_ID}" | python3 -c "
import json,sys; g=json.load(sys.stdin)['gateway']
print('    same again     cache_hit=%-5s similarity=%.4f' % (g['cache_hit'], g['cache_similarity']))"
ask "what is the capital of france ${RUN_ID}?" | python3 -c "
import json,sys; g=json.load(sys.stdin)['gateway']
print('    reworded       cache_hit=%-5s similarity=%.4f' % (g['cache_hit'], g['cache_similarity']))"
ask "What is the capital of Japan ${RUN_ID}" | python3 -c "
import json,sys; g=json.load(sys.stdin)['gateway']
print('    different      cache_hit=%-5s provider=%s' % (g['cache_hit'], g['provider']))"
echo

echo "==> Streaming (first SSE chunks)"
# Captured into a variable rather than piped straight into head: closing the pipe
# early sends curl a SIGPIPE, and under `set -o pipefail` that aborts the script.
STREAM_OUTPUT="$(curl -sN -X POST "${GATEWAY}/v1/chat/completions" \
  -H 'Authorization: Bearer demo-key-pro' -H 'Content-Type: application/json' \
  -d "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"stream this back ${RUN_ID}\"}]}")"
echo "${STREAM_OUTPUT}" | head -4
echo "    ... $(echo "${STREAM_OUTPUT}" | grep -c '^data:') events in total"
echo

echo "==> Current usage for that tenant"
curl -sS "${GATEWAY}/v1/usage" -H 'Authorization: Bearer demo-key-pro' | python3 -m json.tool
echo

echo "==> An unknown key is rejected"
curl -s -o /dev/null -w '    status: %{http_code}\n' \
  -X POST "${GATEWAY}/v1/chat/completions" \
  -H 'Authorization: Bearer nope' -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
echo

echo "==> An invalid role is rejected with problem details"
curl -sS -X POST "${GATEWAY}/v1/chat/completions" \
  -H 'Authorization: Bearer demo-key-pro' -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"wizard","content":"hi"}]}' \
  | python3 -m json.tool
echo

echo "==> Exhausting the free tier's 5000-token budget"
# Each prompt must be distinct. Repeating one would hit the cache from the second
# request onward, and cache hits deliberately do not consume budget -- so the
# tenant would never run out and this would demonstrate nothing.
PAD="$(head -c 4000 < /dev/zero | tr '\0' 'x')"
for i in $(seq 1 10); do
  status=$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST "${GATEWAY}/v1/chat/completions" \
    -H 'Authorization: Bearer demo-key-free' -H 'Content-Type: application/json' \
    -d "{\"messages\":[{\"role\":\"user\",\"content\":\"budget probe ${RUN_ID} ${i} ${PAD}\"}]}")
  printf '%s ' "${status}"
done
echo
echo "    (200 until the budget runs out, then 429)"
