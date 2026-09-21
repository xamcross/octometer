package octometer.monitor

import kotlinx.serialization.Serializable

/** The one error body shape of the API. It never holds a connection string. */
@Serializable
data class ErrorBody(val error: String)
