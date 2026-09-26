package octometer.monitor.mongo

import org.bson.Document

/** The reason text of a failed check 1 (design decision D9, issue #30). */
internal const val REASON_CHECK1_FAILED = "privilege check 1 failed: a collection outside the configured one is visible"

/** The reason text of a failed check 2 (design decision D9, issue #30). */
internal const val REASON_CHECK2_FAILED = "privilege check 2 failed: the privilege list differs from the allow-list"

/** The reason text of the no-evidence case (design decision D9, issue #30). */
internal const val REASON_NO_EVIDENCE =
    "privilege check failed: no evidence (no privilege list and no visible collection)"

private const val FIND_ACTION = "find"

/**
 * The verdict of the privilege check of design decision D9 (issue
 * #30). [Failed.reason] names the failed check only. It never holds a
 * collection name, a user name, a role name, or a privilege.
 */
internal sealed class PrivilegeVerdict {
    object Ok : PrivilegeVerdict()
    data class Failed(val reason: String) : PrivilegeVerdict()
}

/**
 * Checks the privileges of the database user against design decision
 * D9 and section 2.4 (issue #30, a production blocker). This is a
 * pure function: [MongoAppReader] reads the two MongoDB commands, and
 * this function only judges their result.
 *
 * Check 1 reads [visibleCollections], the collection names of
 * `listCollections` with `authorizedCollections: true`. An empty
 * list passes: a new app has no collection before its first event. A
 * list equal to `[collection]` passes too. Each other list fails,
 * with [REASON_CHECK1_FAILED].
 *
 * Check 2, the main check, reads
 * `authInfo.authenticatedUserPrivileges` of [connectionStatus]. The
 * list must hold exactly one entry, with `resource.db == database`,
 * `resource.collection == collection`, and `actions == ["find"]`.
 * Each other form fails, with [REASON_CHECK2_FAILED]: a second entry,
 * a second action, a cluster resource, or an empty collection.
 *
 * When the privilege list is absent or empty, check 1 alone decides.
 * An empty [visibleCollections] then gives no evidence of any kind,
 * and the verdict fails with [REASON_NO_EVIDENCE].
 *
 * `authenticatedUserRoles` decides nothing here. This function never
 * reads it.
 */
internal fun evaluatePrivileges(
    connectionStatus: Document?,
    visibleCollections: List<String>,
    database: String,
    collection: String,
): PrivilegeVerdict {
    val check1Failed = visibleCollections.isNotEmpty() && visibleCollections != listOf(collection)
    if (check1Failed) return PrivilegeVerdict.Failed(REASON_CHECK1_FAILED)

    val privileges = authenticatedUserPrivileges(connectionStatus)
    if (privileges.isNullOrEmpty()) {
        return if (visibleCollections.isEmpty()) PrivilegeVerdict.Failed(REASON_NO_EVIDENCE) else PrivilegeVerdict.Ok
    }

    return if (matchesAllowList(privileges, database, collection)) {
        PrivilegeVerdict.Ok
    } else {
        PrivilegeVerdict.Failed(REASON_CHECK2_FAILED)
    }
}

@Suppress("UNCHECKED_CAST")
private fun authenticatedUserPrivileges(connectionStatus: Document?): List<Document>? {
    val authInfo = connectionStatus?.get("authInfo") as? Document ?: return null
    return authInfo.get("authenticatedUserPrivileges") as? List<Document>
}

private fun matchesAllowList(privileges: List<Document>, database: String, collection: String): Boolean {
    if (privileges.size != 1) return false
    val privilege = privileges.single()
    val resource = privilege.get("resource") as? Document ?: return false
    val resourceDatabase = resource.getString("db")
    val resourceCollection = resource.getString("collection")
    val actions = privilege.get("actions") as? List<*> ?: return false
    return resourceDatabase == database &&
        !resourceCollection.isNullOrEmpty() &&
        resourceCollection == collection &&
        actions == listOf(FIND_ACTION)
}
