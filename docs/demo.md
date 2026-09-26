# The Octometer demo

This guide starts the demo app and the monitor. It proves the path from a
click on the demo page to a number on the level 1 view. Run each command
in Windows PowerShell, from the root of the repository, unless a step
says a different folder. `curl` is an alias of `curl.exe` in PowerShell;
this guide uses the real name.

Read `tools/demo-app/README.md`, `monitor/backend/README.md`, and
`monitor/frontend/README.md` for more detail on each part.

## 1. Start the demo database

```powershell
docker compose -f tools/demo-app/docker-compose.yml up -d
```

Folder: the repository root. Port: 27017 on the loopback address. Expected
output line: `Container demo-app-mongo-1  Started`.

Set `OCTOMETER_DEMO_MONGO_PORT` before this command when port 27017
already holds a different MongoDB server on this machine. A new port
number then changes three places together, not this one place alone:

- `OCTOMETER_DEMO_MONGO_PORT`, before this command, for the container.
- `OCTOMETER_DEMO_MONGO_URI`, before step 2, for the demo app. Give it
  the value `mongodb://127.0.0.1:<port>`, with the same port number.
- The `connectionString` field of step 5. Give it the same URI.

## 2. Write the synthetic clicks

```powershell
./gradlew.bat :tools:demo-app:run --args="--generate"
```

Folder: the repository root. This command writes 150 events for 5 demo
user ids, then it stops. Expected output line: `The generator wrote 150
events for 5 user ids.`

## 3. Start the demo app

```powershell
./gradlew.bat :tools:demo-app:run
```

Folder: the repository root. Port: 8098. Expected output line:
`Responding at http://127.0.0.1:8098`. Open `http://127.0.0.1:8098/` in
a browser. Select a demo user first. Click a button only after that
selection.

## 4. Start the monitor

Octometer has two forms of the monitor. Pick one.

### 4a. The dev form

```powershell
./gradlew.bat :monitor:backend:run
```

Folder: the repository root. Port: 7431. This task sets `OCTOMETER_MODE`
to `dev`. The dev mode polls each app every 5 seconds. Expected output
line: `Responding at http://127.0.0.1:7431`.

```powershell
cd monitor/frontend
npm ci
npm start
```

Folder: `monitor/frontend`. Port: 4200. Expected output line: `Local:
http://localhost:4200/`. Open `http://localhost:4200/apps` in a browser.
Use `localhost`, not `127.0.0.1`: the dev server of the frontend listens
on `localhost` only.

### 4b. The distribution form (issue #38)

```powershell
./gradlew.bat :monitor:backend:distZip
```

Folder: the repository root. This task builds the Angular app of
`monitor/frontend` into the zip. Output file:
`monitor/backend/build/distributions/backend-0.1.0.zip`. Extract this
file, then run `bin\backend.bat` from the extracted folder.

The distribution form starts in the prod mode by default. The prod mode
polls each app every 60 seconds. Port: 7431, the same bundled default as
the dev form. Open `http://localhost:7431/apps` in a browser. The
distribution serves the Angular app itself, so this form needs no
separate frontend command.

## 5. Register the demo app

Send this request once. A JSON body in one PowerShell line breaks on the
quotes. Windows PowerShell 5.1 removes each double quote of a variable,
when it passes that variable to a native program. Save the body to a
file instead.

Build the JSON in a variable first:

```powershell
$json = @'
{
  "name": "demo",
  "connectionString": "mongodb://127.0.0.1:27017",
  "database": "exampledb",
  "collection": "octometer_events"
}
'@
```

Write the variable to a file named `demo-app.json`, as UTF-8 with no
byte order mark. `Set-Content -Encoding utf8` of PowerShell 5.1 adds a
byte order mark. That mark breaks the JSON. The server then gives
`400`. Use `WriteAllText` instead. It adds no mark:

```powershell
[IO.File]::WriteAllText("$PWD\demo-app.json", $json)
```

Send the file:

```powershell
curl.exe -X POST "http://127.0.0.1:7431/api/apps" `
  -H "Content-Type: application/json" `
  -H "Origin: http://127.0.0.1:7431" `
  --data-binary "@demo-app.json"
```

The connection string names no user and no password: the compose
database of step 1 needs neither. `RequestGuard` (design decision D12)
accepts the `Origin` header of this command for both forms of the
monitor. A successful answer has the status `201` and an `appId`.

## 6. Read the demo row

```powershell
curl.exe "http://127.0.0.1:7431/api/apps"
```

The answer holds one row for the app `demo`: the clicks, the unique
users, the unique sessions, and the status. After step 2 and step 5,
this row shows a minimum of 100 clicks and 5 unique users.

## 7. Click a real button

Go back to the demo page of step 3. Select a user in the select box.
Click one element that carries a `data-octo` attribute. Note the
wall-clock time of this click.

Run the command of step 6 again, once each second. The click count goes
up in a maximum of 13 seconds: 1 second for the flush of the tracker, 2
seconds for the settle lag of the dev mode, 5 seconds for the poll
interval of the dev mode, and 5 seconds for the refresh interval of the
frontend. The distribution form uses the prod mode, with a settle lag of
60 seconds and a poll interval of 60 seconds; its worst delay is longer.

Open the view of step 4 again. The row now shows the new click.

## 8. Stop the demo

Stop each process of step 2, step 3, and step 4 with Ctrl+C. Then remove
the database container and its data:

```powershell
docker compose -f tools/demo-app/docker-compose.yml down -v
```
