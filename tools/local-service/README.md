# The local run of the hosted image

Issue #295, step 7. This folder runs the same Docker image as the Fly
deploy, on your own machine, with a local MongoDB container in place
of Atlas. Use it to check the image before a real deploy.

## Before you run it

1. Build the install folder once:
   `./gradlew :monitor:backend:installDist`
2. Docker Desktop runs.

## Run it

```
docker compose -f tools/local-service/docker-compose.yml up --build
```

The monitor answers on `http://127.0.0.1:8080`. MongoDB answers on
`127.0.0.1:27018`. Each port stays off the owner's own ports 7431,
8098, and 27117, so this run works beside the desktop app, the demo
app, and the end-to-end suite.

Check the health route:

```
curl http://127.0.0.1:8080/healthz
```

It answers `ok`. `GET /api/health` needs the header
`X-Octometer-Admin: local-run-token` (the fixed token of the compose
file, never a real secret).

## Stop it

```
docker compose -f tools/local-service/docker-compose.yml down
```

Add `-v` to also remove the data volumes.

## What this run does not prove

This compose file gives no real Atlas cluster and no real Resend
account. `OCTOMETER_CLUSTER_URI` here is a loopback placeholder; #297
wires the cluster URI into a real MongoDB read. This run proves the
image build and the health route only.
