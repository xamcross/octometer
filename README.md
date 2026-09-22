# Octometer

Octometer is a monitoring tool. Each app sends click events to a monitor
server. The monitor stores the events and shows the totals in a browser
view.

Read `App Description.md` for the product description. Read
`docs/superpowers/specs/2026-09-21-octometer-design.md` for the design
decisions and the contract rules.

`./gradlew build` needs Node 24 and npm on the PATH for the demo app
(Ktor review MAJOR 3, pull request #168). Read
`tools/demo-app/README.md` for the setup steps.
