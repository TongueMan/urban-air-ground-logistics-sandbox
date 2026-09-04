#!/bin/sh
set -eu
: "${FRONTEND_BAIDU_MAP_AK:=}"
: "${FRONTEND_MEDIA_BASE:=/media}"
sed -e "s#\${FRONTEND_BAIDU_MAP_AK}#${FRONTEND_BAIDU_MAP_AK}#g" -e "s#\${FRONTEND_MEDIA_BASE}#${FRONTEND_MEDIA_BASE}#g" /opt/zhixun/config.template.js > /tmp/zhixun-config.js

