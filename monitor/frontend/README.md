# Octometer frontend

This is the Angular app of Octometer. It shows the app list and the data of
each app.

## Node version

The app needs Node 22 from v22.22.3, or Node 24 from v24.15.0, or Node 26
and later. Node 23 and Node 25 do not work. File `.nvmrc` names the exact
tested version.

## Scripts

Run each script from this folder.

- `npm start` — starts the dev server on port 4200.
- `npm test` — runs the unit tests one time, then stops.
- `npm run test:watch` — runs the unit tests, and watches each file change.
- `npm run test:changed` — runs only the specs of a file changed against `origin/main`.
- `npm run lint` — checks the code style with ESLint.
- `npm run build` — builds the app into `dist/frontend`.
- `npm run format` — writes each file in the Prettier code style.
- `npm run format:check` — checks the Prettier code style, and changes no file.

## Dev proxy

The dev server sends each request under `/api` to the monitor server at
`http://127.0.0.1:7431`. File `proxy.conf.json` holds this rule.

The dev server itself listens on `localhost` (`[::1]`), and not on
`127.0.0.1`. Use `http://localhost:4200` to open or to check the app.
`http://127.0.0.1:4200` does not connect.
