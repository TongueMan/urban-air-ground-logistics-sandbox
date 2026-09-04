#!/bin/sh
set -eu

host=${MEDIA_RTSP_HOST:-mediamtx}
input_dir=${MEDIA_INPUT_DIR:-/media-input}
routes=${MEDIA_FILE_ROUTES:-patrol_uav_cam_01=patrol_uav_cam_01.mp4,patrol_uav_cam_02=patrol_uav_cam_02.mp4,patrol_wash_01=patrol_wash_01.mp4}
pids=""

stop_publishers() {
  rm -f /tmp/zhixun-media.ready
  [ -z "$pids" ] || kill $pids 2>/dev/null || true
  wait 2>/dev/null || true
}
trap stop_publishers INT TERM EXIT

old_ifs=$IFS
IFS=,
for route in $routes; do
  stream=${route%%=*}
  file=${route#*=}
  [ "$stream" != "$file" ] || { echo "Invalid media route: $route" >&2; exit 1; }
  [ -r "$input_dir/$file" ] || { echo "Missing media file: $input_dir/$file" >&2; exit 1; }
  ffmpeg -nostdin -hide_banner -loglevel warning -re -stream_loop -1 -i "$input_dir/$file" \
    -map 0:v:0 -an -c:v copy -rtsp_transport tcp -f rtsp "rtsp://$host:8554/$stream" &
  pids="$pids $!"
done
IFS=$old_ifs

sleep 4
for pid in $pids; do kill -0 "$pid" 2>/dev/null || { echo "A publisher failed during startup" >&2; exit 1; }; done
touch /tmp/zhixun-media.ready

while :; do
  for pid in $pids; do kill -0 "$pid" 2>/dev/null || exit 1; done
  sleep 3
done
