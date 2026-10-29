package com.slovarik.app

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class CloudSyncPhase { UNCONFIGURED, SIGNED_OUT, READY, SYNCING, ERROR, ACCOUNT_MISMATCH }

data class CloudSyncState(
    val configured: Boolean,
    val email: String? = null,
    val phase: CloudSyncPhase,
    val lastSync: Long = 0
)

/** Shared by the foreground controller and WorkManager, including while an activity is recreated. */
internal object CloudSyncRuntime {
    val mutex = Mutex()
    val studying = AtomicBoolean(false)
    private val studyGate = Any()
    val incomingRevision = MutableStateFlow(0L)
    private var cacheConfigured = false // Accessed under mutex (or during controller construction).

    fun setStudying(active: Boolean) { synchronized(studyGate) { studying.set(active) } }

    fun configuration(context: Context): String? {
        fun resource(name: String): String? {
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            return if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
        }
        return if (resource("google_app_id") == null) null else resource("default_web_client_id")
    }

    fun auth(context: Context): FirebaseAuth {
        if (FirebaseApp.getApps(context).isEmpty()) checkNotNull(FirebaseApp.initializeApp(context))
        return FirebaseAuth.getInstance()
    }

    private fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance().apply {
        if (!cacheConfigured) {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            cacheConfigured = true
        }
    }

    private val identifier = Regex("[a-fA-F0-9-]{32,36}")
    private val recordIdentifier = Regex("(?:progress-)?[a-fA-F0-9-]{32,36}")
    private val kinds = setOf("folders", "decks", "cards", "progress", "events", "settings", "match", "undo")

    private fun decode(data: Map<String, Any>?, documentId: String): SyncRecord {
        require(data != null && (data["schema"] as? Number)?.toInt() == 1)
        val id = data["id"] as? String ?: error("Invalid sync id")
        val kind = data["kind"] as? String ?: error("Invalid sync kind")
        val stamp = (data["stamp"] as? Number)?.toLong() ?: error("Invalid sync stamp")
        val device = data["device"] as? String ?: error("Invalid sync device")
        val deleted = data["deleted"] as? Boolean ?: error("Invalid sync tombstone")
        val fields = data["fields"] as? Map<*, *> ?: error("Invalid sync fields")
        require(id == documentId && recordIdentifier.matches(id) && identifier.matches(device) && kind in kinds && stamp > 0)
        require(fields.keys.all { it is String })
        @Suppress("UNCHECKED_CAST")
        return SyncRecord(id, kind, stamp, device, deleted, fields as Map<String, Any?>)
    }

    private fun encode(record: SyncRecord): Map<String, Any?> = mapOf(
        "schema" to 1, "id" to record.id, "kind" to record.kind, "stamp" to record.stamp,
        "device" to record.device, "deleted" to record.deleted, "fields" to canonicalCloudValue(record.fields)
    )

    private fun checkAccount(auth: FirebaseAuth, local: SyncLocalStore, uid: String) {
        if (auth.currentUser?.uid != uid || local.boundAccount() != uid) throw AccountChanged()
    }

    private class AccountChanged : IllegalStateException()
    private class StudyPaused : IllegalStateException()

    /** Returns true when a sync completed, false when study deferred its incoming merge. */
    suspend fun sync(context: Context, store: Store, uid: String): Boolean {
        val local = SyncLocalStore(store)
        val auth = auth(context)
        checkAccount(auth, local, uid)
        if (studying.get()) return false
        withContext(Dispatchers.IO) { UserProfileBridge.migrateLegacy(context, store) }
        val collection = firestore().collection("users").document(uid).collection("records")
        suspend fun pull() {
            checkAccount(auth, local, uid)
            val records = collection.get(Source.SERVER).await().documents.map { decode(it.data, it.id) }
            checkAccount(auth, local, uid)
            withContext(Dispatchers.IO) {
                val changed = synchronized(studyGate) {
                    if (studying.get()) throw StudyPaused()
                    val merged = local.applyRemote(records)
                    UserProfileBridge.project(context, store)
                    merged
                }
                if (changed) incomingRevision.value++
            }
        }
        try {
            pull()
            checkAccount(auth, local, uid)
            val pending = withContext(Dispatchers.IO) { local.pending() }
            var conflicts = false
            for (record in pending) {
                checkAccount(auth, local, uid)
                if (studying.get()) throw StudyPaused()
                require(recordIdentifier.matches(record.id) && identifier.matches(record.device) && record.kind in kinds)
                val ref = collection.document(record.id)
                // A transaction performs a server read. No offline/local write can count as an acknowledgement.
                val accepted = firestore().runTransaction { tx ->
                    val snapshot = tx.get(ref)
                    val remote = if (snapshot.exists()) decode(snapshot.data, snapshot.id) else null
                    require(remote == null || remote.kind == record.kind) { "Conflicting sync identity" }
                    when {
                        remote == null || compareSyncVersions(record, remote) > 0 -> {
                            tx.set(ref, encode(record)); true
                        }
                        compareSyncVersions(record, remote) == 0 -> {
                             require(encode(remote) == encode(record)) { "Conflicting sync identity" }; true
                        }
                        else -> false
                    }
                }.await()
                checkAccount(auth, local, uid)
                if (accepted) withContext(Dispatchers.IO) { local.acknowledge(listOf(record)) }
                else conflicts = true
            }
            // A concurrent remote change during a transaction may not have appeared in the first snapshot.
            if (conflicts) pull()
            checkAccount(auth, local, uid)
            return true
        } catch (_: StudyPaused) {
            return false
        }
    }

    fun permanent(error: Throwable): Boolean = when (error) {
        is AccountChanged, is IllegalArgumentException, is SecurityException -> true
        is FirebaseFirestoreException -> error.code in setOf(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
            FirebaseFirestoreException.Code.FAILED_PRECONDITION,
            FirebaseFirestoreException.Code.INVALID_ARGUMENT
        )
        else -> false
    }

    fun accountChanged(error: Throwable): Boolean = error is AccountChanged
}

class CloudSyncController(context: Context, store: Store, enableCloud: Boolean = true) : Closeable {
    private val app = context.applicationContext
    private val databaseName = store.databaseName
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configured = enableCloud && CloudSyncRuntime.configuration(app) != null
    private val mutableState = MutableStateFlow(CloudSyncState(configured, phase = if (configured) CloudSyncPhase.SIGNED_OUT else CloudSyncPhase.UNCONFIGURED))
    val state: StateFlow<CloudSyncState> = mutableState

    init {
        if (configured) scope.launch {
            try {
                CloudSyncRuntime.mutex.withLock {
                    if (closed.get()) return@withLock
                    val bound = withContext(Dispatchers.IO) {
                        Store(app, checkNotNull(databaseName)).use { SyncLocalStore(it).boundAccount() }
                    }
                    val user = CloudSyncRuntime.auth(app).currentUser
                    if (bound != null && user?.uid == bound) {
                        mutableState.value = CloudSyncState(true, user.email, CloudSyncPhase.READY)
                        CloudSyncWorker.schedule(app)
                    } else if (user != null) {
                        CloudSyncRuntime.auth(app).signOut()
                        mutableState.value = CloudSyncState(true, phase = if (bound == null) CloudSyncPhase.SIGNED_OUT else CloudSyncPhase.ACCOUNT_MISMATCH)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.value = CloudSyncState(true, phase = CloudSyncPhase.ERROR) }
        }
    }

    suspend fun signIn(activity: Activity) {
        if (!configured || closed.get()) return
        val caller = currentCoroutineContext()[Job]
        try {
            CloudSyncRuntime.mutex.withLock {
                if (closed.get()) return@withLock
                mutableState.value = mutableState.value.copy(phase = CloudSyncPhase.SYNCING)
                val option = GetSignInWithGoogleOption.Builder(checkNotNull(CloudSyncRuntime.configuration(app))).build()
                val response = CredentialManager.create(app).getCredential(activity,
                    GetCredentialRequest.Builder().addCredentialOption(option).build())
                val credential = response.credential
                require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val auth = CloudSyncRuntime.auth(app)
                // Firebase Tasks cannot be cancelled. Finish exchange + binding under the
                // mutex, or sign out its result, before another login is allowed to start.
                withContext(NonCancellable) {
                    val user = checkNotNull(auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user)
                    if (closed.get() || caller?.isActive == false) {
                        auth.signOut()
                        return@withContext
                    }
                    val bound = withContext(Dispatchers.IO) {
                        Store(app, checkNotNull(databaseName)).use { SyncLocalStore(it).bindAccount(user.uid) }
                    }
                    if (!bound) {
                        auth.signOut()
                        CloudSyncWorker.cancel(app)
                        mutableState.value = CloudSyncState(true, phase = CloudSyncPhase.ACCOUNT_MISMATCH)
                        return@withContext
                    }
                    if (closed.get() || caller?.isActive == false) {
                        // A completed binding records already-granted first-link consent,
                        // but a cancelled screen must not leave a signed-in session running.
                        auth.signOut()
                        mutableState.value = CloudSyncState(true, phase = CloudSyncPhase.SIGNED_OUT)
                        return@withContext
                    }
                    mutableState.value = CloudSyncState(true, user.email, CloudSyncPhase.READY)
                    CloudSyncWorker.schedule(app)
                    CloudSyncWorker.enqueue(app)
                }
            }
        } catch (cancel: CancellationException) {
            if (!closed.get()) mutableState.value = mutableState.value.copy(phase = CloudSyncPhase.SIGNED_OUT)
            throw cancel
        }
        catch (_: GetCredentialCancellationException) {
            mutableState.value = CloudSyncState(true, phase = CloudSyncPhase.SIGNED_OUT)
        }
        catch (error: Exception) {
            if (CloudSyncRuntime.accountChanged(error)) {
                CloudSyncRuntime.auth(app).signOut()
                CloudSyncWorker.cancel(app)
                mutableState.value = CloudSyncState(true, phase = CloudSyncPhase.ACCOUNT_MISMATCH)
            } else mutableState.value = mutableState.value.copy(phase = CloudSyncPhase.ERROR)
        }
    }

    suspend fun signOut() {
        if (!configured || closed.get()) return
        try {
            CloudSyncRuntime.mutex.withLock {
                CloudSyncWorker.cancel(app)
                CloudSyncRuntime.auth(app).signOut()
                mutableState.value = CloudSyncState(true, phase = CloudSyncPhase.SIGNED_OUT)
                CredentialManager.create(app).clearCredentialState(ClearCredentialStateRequest())
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { mutableState.value = CloudSyncState(true, phase = CloudSyncPhase.ERROR) }
    }

    suspend fun syncNow() {
        if (!configured || closed.get() || mutableState.value.phase !in setOf(CloudSyncPhase.READY, CloudSyncPhase.ERROR)) return
        try {
            CloudSyncRuntime.mutex.withLock {
                if (closed.get()) return@withLock
                val auth = CloudSyncRuntime.auth(app)
                val uid = auth.currentUser?.uid
                val bound = withContext(Dispatchers.IO) {
                    Store(app, checkNotNull(databaseName)).use { SyncLocalStore(it).boundAccount() }
                }
                if (uid == null || bound == null || uid != bound) {
                    if (uid != null && uid != bound) {
                        auth.signOut()
                        CloudSyncWorker.cancel(app)
                    }
                    mutableState.value = CloudSyncState(true, phase = if (uid != null) CloudSyncPhase.ACCOUNT_MISMATCH else CloudSyncPhase.SIGNED_OUT)
                    return@withLock
                }
                mutableState.value = mutableState.value.copy(phase = CloudSyncPhase.SYNCING)
                val done = withTimeout(60_000) {
                    withContext(Dispatchers.IO) {
                        Store(app, checkNotNull(databaseName)).use { CloudSyncRuntime.sync(app, it, uid) }
                    }
                }
                mutableState.value = mutableState.value.copy(phase = CloudSyncPhase.READY,
                    lastSync = if (done) System.currentTimeMillis() else mutableState.value.lastSync)
                if (!done) CloudSyncWorker.enqueue(app)
            }
        } catch (_: TimeoutCancellationException) {
            if (!closed.get()) mutableState.value = mutableState.value.copy(phase = CloudSyncPhase.ERROR)
            CloudSyncWorker.enqueue(app)
        } catch (cancel: CancellationException) {
            if (!closed.get()) mutableState.value = mutableState.value.copy(phase = CloudSyncPhase.READY)
            throw cancel
        }
        catch (error: Exception) {
            if (CloudSyncRuntime.accountChanged(error)) {
                CloudSyncRuntime.auth(app).signOut()
                CloudSyncWorker.cancel(app)
                mutableState.value = CloudSyncState(true, phase = CloudSyncPhase.ACCOUNT_MISMATCH)
            } else {
                mutableState.value = mutableState.value.copy(phase = CloudSyncPhase.ERROR)
                if (!CloudSyncRuntime.permanent(error)) CloudSyncWorker.enqueue(app)
            }
        }
    }

    fun setStudyActive(active: Boolean) {
        val wasActive = CloudSyncRuntime.studying.get()
        CloudSyncRuntime.setStudying(active)
        if (wasActive && !active && configured && mutableState.value.phase == CloudSyncPhase.READY) CloudSyncWorker.enqueue(app)
    }

    override fun close() { closed.set(true); scope.cancel() }

    companion object {
        val incomingRevision: StateFlow<Long> = CloudSyncRuntime.incomingRevision
    }
}
