package octometer.monitor

import kotlinx.serialization.Serializable

/** The one error body shape of the API. It never holds a connection string. */
@Serializable
data class ErrorBody(val error: String)

// Correction round 1 of issue #38 (MINOR 1, security review): ApiRoutes.kt
// and StaticFrontend.kt each answer 404 for a path below "/api/". One
// shared constant keeps the message text equal in each answer.
const val UNKNOWN_API_ROUTE_MESSAGE = "The route does not exist."
