package octometer.monitor.erasure

import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.collect
import org.bson.Document

/** The collection name of contract rule C1, the same name as [octometer.monitor.collect.CollectorStore]. */
private const val COLLECTION_NAME = "octometer_events"

/** The pass bound of contract rule C43, the same bound as [eraseUserEvents]. */
private const val MAX_CLUSTER_DELETE_PASSES = 3

/**
 * Deletes each document of [userId], and each anonymous document
 * (`userId: null`) of a session of [userId], in the collection
 * `octometer_events` of [databaseName] (issue #300, design decision D55,
 * contract rule C43). This is the native coroutine equivalent of
 * [octometer.kit.mongo.store.MongoEventLogStore.deleteByUserId]: the
 * coroutine driver's own calls are suspend functions, so this class
 * reimplements the same pass rule natively, the same reason as the
 * class comment of [octometer.monitor.collect.CollectorStore].
 *
 * The caller must run this call before the SQLite cache erasure of
 * [eraseUserEvents] (the correction round of 2026-10-01, point 21): a
 * poll cycle between the two steps would restore the rows that this
 * call removes.
 *
 * [userId] reaches no exception message and no log line (design
 * decision D15); a caller must keep it out of its own log line too.
 */
suspend fun eraseUserEventsFromCluster(client: MongoClient, databaseName: String, userId: String) {
    val collection = client.getDatabase(databaseName).getCollection<Document>(COLLECTION_NAME)
    var sessionIds = sessionIdsOfUser(collection, userId)
    var pass = 0
    while (sessionIds.isNotEmpty() && pass < MAX_CLUSTER_DELETE_PASSES) {
        collection.deleteMany(Filters.and(Filters.eq("userId", null), Filters.`in`("sessionId", sessionIds.toList())))
        collection.deleteMany(Filters.and(Filters.eq("userId", userId), Filters.`in`("sessionId", sessionIds.toList())))
        sessionIds = sessionIdsOfUser(collection, userId)
        pass++
    }
}

private suspend fun sessionIdsOfUser(collection: MongoCollection<Document>, userId: String): Set<String> {
    val sessionIds = mutableSetOf<String>()
    collection.find(Filters.eq("userId", userId)).collect { document ->
        document.getString("sessionId")?.let { sessionIds += it }
    }
    return sessionIds
}
