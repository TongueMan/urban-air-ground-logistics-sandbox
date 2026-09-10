FROM alpine:3.20
RUN apk add --no-cache ffmpeg
COPY --chmod=0755 entrypoint.sh /usr/local/bin/skyfleet-media-publisher
ENTRYPOINT ["/usr/local/bin/skyfleet-media-publisher"]
