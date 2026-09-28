package octometer.monitor.config

/**
 * Issue #295, brief review B2: a public bind in prod mode needs the
 * admin token, the temporary guard of D52 until #301 removes it. A
 * desktop run (prod mode, bind 127.0.0.1) needs it not, so the local
 * run and the suite stay as they are.
 *
 * The caller of a missing token exits the process with code 2, the
 * same exit code as an invalid config value.
 *
 * The issue also names a clear start error for a missing cluster URI
 * in this same case (step 4). This function checks the admin token
 * only: the pull request text names the reason, and asks the
 * maintainer to confirm it, because the CI image check of brief
 * review M9, M10 runs the image with no cluster URI and still needs
 * `/healthz` to answer `ok`. #297 wires clusterUri into a real
 * connection and can add its own start check then.
 *
 * @throws InvalidConfigException the admin token is missing, and the
 *   bind address is not 127.0.0.1, in prod mode.
 */
fun requireHostedSecrets(config: MonitorConfig) {
    if (config.mode != "prod" || config.bind == "127.0.0.1") return
    if (config.adminToken.isBlank()) {
        throw InvalidConfigException(
            "The value of adminToken is missing. Set OCTOMETER_ADMIN_TOKEN when the server binds beyond 127.0.0.1.",
        )
    }
}
