package octometer.demo

/**
 * The settings of the demo app (issue #14). The app binds to a loopback
 * address and a fixed port. It reads the MongoDB URI from an environment
 * variable, with a loopback default for the compose file. The database
 * name in each text and example of this module is `exampledb`.
 */
data class DemoSettings(
    val host: String,
    val port: Int,
    val mongoUri: String,
    val databaseName: String,
    // Issue #297 (D50): the hosted collector of the end-to-end suite.
    // A blank collectorUrl (the standalone run, with no suite) keeps
    // the demo app's own kit ingest route.
    val collectorUrl: String = "",
    val appKey: String = "",
) {
    companion object {
        /** The fixed port of the demo app (issue #14). */
        const val DEFAULT_PORT: Int = 8098

        /** The loopback default of `docker-compose.yml`. */
        const val DEFAULT_MONGO_URI: String = "mongodb://127.0.0.1:27017"

        /** The default database name of the demo app. */
        const val DEFAULT_DATABASE_NAME: String = "exampledb"

        /**
         * Reads `OCTOMETER_DEMO_PORT`, `OCTOMETER_DEMO_MONGO_URI`,
         * `OCTOMETER_DEMO_DATABASE`, `OCTOMETER_DEMO_COLLECTOR_URL`, and
         * `OCTOMETER_DEMO_APP_KEY` from the process environment. A
         * missing variable, and a port value with no valid whole
         * number, each fall back to the default (issue #297: the last
         * two default to a blank text).
         */
        fun fromEnvironment(): DemoSettings = DemoSettings(
            host = "127.0.0.1",
            port = System.getenv("OCTOMETER_DEMO_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
            mongoUri = System.getenv("OCTOMETER_DEMO_MONGO_URI") ?: DEFAULT_MONGO_URI,
            databaseName = System.getenv("OCTOMETER_DEMO_DATABASE") ?: DEFAULT_DATABASE_NAME,
            collectorUrl = System.getenv("OCTOMETER_DEMO_COLLECTOR_URL") ?: "",
            appKey = System.getenv("OCTOMETER_DEMO_APP_KEY") ?: "",
        )
    }
}
