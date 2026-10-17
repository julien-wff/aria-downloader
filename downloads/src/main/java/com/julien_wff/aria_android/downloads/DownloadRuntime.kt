package com.julien_wff.aria_android.downloads

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.provider.Settings
import android.net.ConnectivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkRequest
import android.net.NetworkCapabilities
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

    fun preferences(context: Context): DownloadPreferences = runtime(context).preferences

    fun controller(context: Context): DownloadController = runtime(context).controller

    internal fun runtime(context: Context): Runtime = instance ?: synchronized(this) {
        instance ?: Runtime(context.applicationContext).also { instance = it }
    }

    internal class Runtime(context: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val preferences = DownloadPreferences(context)
        private val store = RoomTransferStore(context)
        private val scheduler = context.getSystemService(JobScheduler::class.java)
        val notifications = TransferNotifications(context)
        val token: String get() = controller.authorizationToken
        private val requireResume = interruptedByUserOrReboot(context)
        val controller: DownloadController = DownloadController(NativeDownloadEngine(context), store,
            object : TransferScheduler {
                override fun schedule(token: String): Boolean {
                    val job = JobInfo.Builder(1001, ComponentName(context, TransferJobService::class.java))
                        .setUserInitiated(true)
                        .setRequiredNetwork(NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .apply { if (controller.batchWifiOnly) {
                                addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                            } }.build())
                        .setEstimatedNetworkBytes(controller.tasks.value.filter { it.state.canPause }.let { tasks ->
                            if (tasks.any { it.state.totalBytes <= 0 }) JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
                            else tasks.fold(0L) { sum, task ->
                                val remaining = (task.state.totalBytes - task.state.completedBytes).coerceAtLeast(0)
                                if (Long.MAX_VALUE - sum < remaining) Long.MAX_VALUE else sum + remaining
                            }
                        }, JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                        .setExtras(PersistableBundle().apply { putString("token", token) })
                        .build()
                    return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
                }
                override fun cancel() { scheduler.cancel(1001) }
            }, scope, DownloadExporter(File(context.noBackupFilesDir, "aria2"), DownloadsDestination(context)),
            requireExplicitResume = requireResume)

        init {
            // Render transitions immediately, coalesce high-frequency native progress to 1 Hz.
            scope.launch {
                var last: List<DownloadTask> = emptyList()
                var lastToken = ""
                var updatedAt = 0L
                while (true) {
                    val current = controller.tasks.value
                    val now = SystemClock.elapsedRealtime()
                    if ((current != last || token != lastToken) && (token != lastToken || last.map { it.id to it.state.status } != current.map { it.id to it.state.status } || now - updatedAt >= 1000)) {
                        notifications.updateTasks(current, token)
                        last = current
                        lastToken = token
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

    fun clear() { file.delete() }

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
            runtime.notifications.summary(runtime.controller.tasks.value, token),
            JOB_END_NOTIFICATION_POLICY_DETACH)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val lease = ExecutionLease(token,
            prepareNetwork = {
                val network = checkNotNull(params.network) { "Job has no assigned network" }
                check(connectivity.bindProcessToNetwork(network)) { "Job network is unavailable" }
            },
            releaseNetwork = { connectivity.bindProcessToNetwork(null); Unit },
        ) {
            if (executions.remove(params) != null) jobFinished(params, false)
        }
        executions[params] = lease
        runtime.controller.authorize(lease)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val lease = executions.remove(params) ?: return false
        return DownloadRuntime.controller(this).revoke(lease, params.stopReason,
            reschedule = params.stopReason != JobParameters.STOP_REASON_USER)
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
                val id = intent.getStringExtra("taskId")
                when (intent.action) {
                    TransferNotifications.PAUSE -> if (id == null) runtime.controller.pause(token) else runtime.controller.pauseTask(id, token)
                    TransferNotifications.CANCEL -> if (id == null) runtime.controller.cancel(token) else runtime.controller.cancelTask(id, token)
                }
            } catch (_: Exception) {
                // In particular, a failed intent write must leave the existing transfer alone.
                // Surface the failure without crashing the process that owns the active job.
                runtime.notifications.controlFailed(runtime.controller.state.value, runtime.token)
            } finally { pending.finish() }
        }
    }
}

/** A new process never schedules work. Reboot/user-stop recovery also fences old leases. */
private fun interruptedByUserOrReboot(context: Context): Boolean {
    val marker = context.getSharedPreferences("execution_recovery", Context.MODE_PRIVATE)
    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    val previousBoot = marker.getInt("boot", boot)
    val previousStart = marker.getLong("started", 0)
    val exit = context.getSystemService(ActivityManager::class.java)
        .getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
    val stopped = exit != null && exit.timestamp > previousStart &&
        exit.reason in setOf(ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED)
    // This small fence is synchronous so a crash cannot acknowledge only half the marker.
    marker.edit().putInt("boot", boot).putLong("started", System.currentTimeMillis()).commit()
    return previousBoot != boot || stopped
}
