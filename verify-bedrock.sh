#!/bin/bash
# Quick Bedrock connectivity check for mkpro
# Usage: source .env 2>/dev/null; ./verify-bedrock.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Mkpro Bedrock Verification ==="

# Check env
if [ -f .env ]; then
  set -a && source .env && set +a
  echo "Loaded .env"
fi

if [ -z "$BEDROCK_API_KEY" ] && [ -z "$AWS_BEARER_TOKEN_BEDROCK" ]; then
  echo "⚠️  No BEDROCK_API_KEY or AWS_BEARER_TOKEN_BEDROCK set. Set one to test Bedrock."
  exit 1
fi

TOKEN="${BEDROCK_API_KEY:-$AWS_BEARER_TOKEN_BEDROCK}"
BASE="${BEDROCK_URL:-https://bedrock-runtime.ap-south-1.amazonaws.com}"
BASE="${BASE%/}"  # strip trailing slash
MODEL="global.anthropic.claude-opus-4-6-v1"
# Same logic as BedrockBaseLM.buildConverseUrl: avoid double /model/
if [[ "$BASE" == */model ]]; then
  URL="${BASE}/${MODEL}/converse"
else
  URL="${BASE}/model/${MODEL}/converse"
fi

echo "URL: $URL"
echo ""

# Minimal Converse request
BODY='{"stream":false,"messages":[{"role":"user","content":[{"text":"Say hello in one word"}]}]}'
RESP=$(curl -s -w "\n%{http_code}" -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "$BODY" 2>/dev/null) || true

HTTP_CODE=$(echo "$RESP" | tail -1)
BODY_RESP=$(echo "$RESP" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
  if echo "$BODY_RESP" | grep -q '"output"\|"Output"'; then
    echo "✅ Bedrock OK - Got valid response (HTTP $HTTP_CODE)"
    echo "$BODY_RESP" | head -c 500
    echo "..."
  else
    echo "⚠️  HTTP 200 but unexpected format. Keys: $(echo "$BODY_RESP" | grep -o '"[^"]*"' | head -10)"
  fi
elif [ "$HTTP_CODE" = "403" ] || [ "$HTTP_CODE" = "401" ]; then
  echo "❌ Auth failed (HTTP $HTTP_CODE). Check BEDROCK_API_KEY."
  echo "$BODY_RESP" | head -c 300
else
  echo "❌ Request failed (HTTP ${HTTP_CODE:-?})"
  echo "$BODY_RESP" | head -c 500
fi
