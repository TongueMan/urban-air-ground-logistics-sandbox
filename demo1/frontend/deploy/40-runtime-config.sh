#!/bin/sh
set -eu
: "${FRONTEND_BAIDU_MAP_AK:=}"
: "${FRONTEND_STATIC_ASSET_BASE:=}"
sed \
  -e "s#\${FRONTEND_BAIDU_MAP_AK}#${FRONTEND_BAIDU_MAP_AK}#g" \
  -e "s#\${FRONTEND_STATIC_ASSET_BASE}#${FRONTEND_STATIC_ASSET_BASE}#g" \
  /opt/skyfleet/config.template.js > /tmp/skyfleet-config.js
