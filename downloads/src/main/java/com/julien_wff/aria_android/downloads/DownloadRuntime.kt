package com.julien_wff.aria_android.downloads

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.AtomicFile
import com.julien_wff.aria_android.aria2.NativeDownloadEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Application wiring entry point; service and Activity always obtain the same process owner. */
object DownloadRuntime {
    @Volatile private var instance: Runtime? = null

    fun controller(context: Context): DownloadController = runtime(context).controller

    internal fun runtime(context: Context): Runtime = instance ?: synchronized(this) {
        instance ?: Runtime(context.applicationContext).also { instance = it }
    }

    internal class Runtime(context: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val store = RoomTransferStore(context)
        private val scheduler = context.getSystemService(JobScheduler::class.java)
        val notifications = TransferNotifications(context)
        val token: String get() = controller.authorizationToken
        val controller: DownloadController = DownloadController(NativeDownloadEngine(context), store,
            object : TransferScheduler {
                override fun schedule(token: String): Boolean {
                    val job = JobInfo.Builder(1001, ComponentName(context, TransferJobService::class.java))
                        .setUserInitiated(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setEstimatedNetworkBytes(controller.state.value.let {
                            if (it.totalBytes > 0) (it.totalBytes - it.completedBytes).coerceAtLeast(0)
                            else JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
                        }, JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                        .setExtras(PersistableBundle().apply { putString("token", token) })
                        .build()
                    return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
                }
                override fun cancel() { scheduler.cancel(1001) }
            }, scope, DownloadExporter(File(context.noBackupFilesDir, "aria2"), DownloadsDestination(context)))

        init {
            // Render transitions immediately, coalesce high-frequency native progress to 1 Hz.
            scope.launch {
                var last: DownloadState? = null
                var updatedAt = 0L
                while (true) {
                    val current = controller.state.value
                    val now = SystemClock.elapsedRealtime()
                    if (current != last && (last?.status != current.status || now - updatedAt >= 1000)) {
                        notifications.update(current, token)
                        last = current
                        updatedAt = now
                    }
                    delay(200)
                }
            }
        }
    }
}

/** Private, excluded from backup; this is a single intent record, not recovery metadata. */
internal class AtomicTransferStore(context: Context) : TransferStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "transfer.json"))

    override suspend fun read(): TransferRecord? = try {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        TransferRecord(json.getString("token"), json.getString("url"), json.getString("fileName"),
            DesiredState.valueOf(json.getString("desired")), DownloadStatus.valueOf(json.getString("status")),
            id = json.optString("id", "legacy-transfer"), createdAt = json.optLong("createdAt", 0))
    } catch (_: Exception) { null }

    override suspend fun write(record: TransferRecord) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("token", record.token).put("url", record.url)
            .put("fileName", record.fileName).put("desired", record.desired.name)
            .put("status", record.status.name).put("id", record.id).put("createdAt", record.createdAt)
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

class TransferJobService : JobService() {
    private val executions = mutableMapOf<JobParameters, ExecutionLease>()

    override fun onStartJob(params: JobParameters): Boolean {
        val runtime = DownloadRuntime.runtime(this)
        val token = params.extras.getString("token") ?: return false
        setNotification(params, TransferNotifications.ID,
            runtime.notifications.build(runtime.controller.state.value, token),
            JOB_END_NOTIFICATION_POLICY_DETACH)
        val lease = ExecutionLease(token) {
            if (executions.remove(params) != null) jobFinished(params, false)
        }
        executions[params] = lease
        runtime.controller.authorize(lease)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val lease = executions.remove(params) ?: return false
        return DownloadRuntime.controller(this).revoke(lease)
    }

    override fun onNetworkChanged(params: JobParameters) {
        executions[params]?.let { DownloadRuntime.controller(this).networkChanged(it) }
    }

    override fun onDestroy() {
        executions.values.toList().forEach { DownloadRuntime.controller(this).revoke(it) }
        executions.clear()
        super.onDestroy()
    }
}

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = DownloadRuntime.runtime(context)
        val pending = goAsync()
        runtime.scope.launch {
            try {
                val token = intent.getStringExtra("token") ?: return@launch
                when (intent.action) {
                    TransferNotifications.PAUSE -> runtime.controller.pause(token)
                    TransferNotifications.CANCEL -> runtime.controller.cancel(token)
                }
            } catch (_: Exception) {
                // In particular, a failed intent write must leave the existing transfer alone.
                // Surface the failure without crashing the process that owns the active job.
                runtime.notifications.controlFailed(runtime.controller.state.value, runtime.token)
            } finally { pending.finish() }
        }
    }
}
