# The Docker image of the hosted monitor (issue #295, D56).
#
# One stage on eclipse-temurin:21-jre. Brief review m10: the CI job
# builds the install folder first, with
# "./gradlew :monitor:backend:installDist". A two-stage Gradle build
# inside the image would repeat that work; this image build stays
# under one minute instead.
FROM eclipse-temurin:21-jre

# octometer:octometer owns /data. A Fly volume mounts as root, with
# the mode 0755, so a Dockerfile USER instruction has no effect there
# (brief review B5). docker-entrypoint.sh runs as root first, fixes
# the ownership, then drops to this user and execs the server.
RUN groupadd --system octometer \
    && useradd --system --gid octometer --home-dir /data --no-create-home octometer

# m7: the memory budget is tight on a 512 MB machine; swap_size_mb in
# fly.toml adds a margin. OCTOMETER_DATA_DIR puts the secrets, the
# logs, and the backups on the volume too (D56). OCTOMETER_MODE=prod
# and OCTOMETER_BIND=0.0.0.0 give the hosted start path of D52.
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m" \
    OCTOMETER_PORT=8080 \
    OCTOMETER_BIND=0.0.0.0 \
    OCTOMETER_DATA_DIR=/data/db \
    OCTOMETER_CLIENT_IP_HEADER=Fly-Client-IP \
    OCTOMETER_MODE=prod

WORKDIR /app
COPY monitor/backend/build/install/backend/ /app/
COPY monitor/backend/docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh /app/bin/backend

EXPOSE 8080

ENTRYPOINT ["/docker-entrypoint.sh"]
