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
 * The collection name of the config document of contract rule C44
 * (design decision D47, issue #276). The privilege check admits this
 * name beside the app's own event collection.
 */
internal const val CONFIG_COLLECTION_NAME = "octometer_config"

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
 * D9 and section 2.4 (issue #30, a production blocker; the two
 * collections of design decision D47, issue #276). This is a pure
 * function: [MongoAppReader] reads the two MongoDB commands, and this
 * function only judges their result.
 *
 * Check 1 reads [visibleCollections], the collection names of
 * `listCollections` with `authorizedCollections: true`. An empty list
 * passes. A list whose names are each one of [collections] passes
 * too, in any order and in any count from one name to every name.
 * Each other list fails, with [REASON_CHECK1_FAILED]. An empty list
 * is rare in practice: a store's constructor already creates its
 * event collection, by creating an index on it, at the app's first
 * start.
 *
 * Check 2, the main check, reads
 * `authInfo.authenticatedUserPrivileges` of [connectionStatus]. Each
 * entry must have `resource.db == database`, a `resource.collection`
 * value that is one name of [collections], and `actions == ["find"]`;
 * two entries must never name the same collection. A privilege list
 * with `find` on one name of [collections] passes, and a list with
 * `find` on every name of [collections] passes too. Each other form
 * fails, with [REASON_CHECK2_FAILED]: a name outside [collections], a
 * second action, a cluster resource, an empty collection, or a
 * repeated collection.
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
    collections: Set<String>,
): PrivilegeVerdict {
    val check1Failed = visibleCollections.isNotEmpty() && !collections.containsAll(visibleCollections)
    if (check1Failed) return PrivilegeVerdict.Failed(REASON_CHECK1_FAILED)

    val privileges = authenticatedUserPrivileges(connectionStatus)
    if (privileges.isNullOrEmpty()) {
        return if (visibleCollections.isEmpty()) PrivilegeVerdict.Failed(REASON_NO_EVIDENCE) else PrivilegeVerdict.Ok
    }

    return if (matchesAllowList(privileges, database, collections)) {
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

/**
 * True when [privileges] holds one entry for one name of [collections],
 * or one entry for each name of [collections], each with
 * `actions == ["find"]` and no two entries for the same collection.
 */
private fun matchesAllowList(privileges: List<Document>, database: String, collections: Set<String>): Boolean {
    if (privileges.isEmpty() || privileges.size > collections.size) return false
    val matchedCollections = mutableSetOf<String>()
    for (privilege in privileges) {
        val resource = privilege.get("resource") as? Document ?: return false
        val resourceDatabase = resource.getString("db")
        val resourceCollection = resource.getString("collection")
        val actions = privilege.get("actions") as? List<*> ?: return false
        if (resourceDatabase != database) return false
        if (resourceCollection.isNullOrEmpty()) return false
        if (resourceCollection !in collections) return false
        if (actions != listOf(FIND_ACTION)) return false
        if (!matchedCollections.add(resourceCollection)) return false
    }
    return true
}
