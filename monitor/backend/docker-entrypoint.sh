#!/bin/sh
# The entrypoint of the hosted image (issue #295, brief review B5).
#
# A Fly volume mounts at /data as root, with the mode 0755. A
# Dockerfile "chown" step has no effect on that mount, because Docker
# applies it before the volume attaches. This script runs as root
# first, fixes the ownership of /data, then drops the root right and
# execs the server. "exec" and "setpriv" replace this shell with the
# JVM, so the JVM becomes process 1 and receives SIGTERM on a stop
# (brief review m9).
set -e

mkdir -p /data
chown -R octometer:octometer /data

exec setpriv --reuid octometer --regid octometer --clear-groups /app/bin/backend
