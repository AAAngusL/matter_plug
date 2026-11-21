package app.matterplug.fixedip

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The backstop of Fixed IP's watch for when Battery Guard and Cellular Check are both off, so no alarm chain calls [IpRunner.onChain]
 * (docs/FIXED-IP.md section 8). A persisted periodic job, every 15 minutes (Android's shortest): it starts the watch service again if
 * Android ended it, and takes one read itself, so a lost Wi-Fi or a drift is still found when the service cannot be restarted.
 * It does not outlive "Force stop" in Android's settings: that cancels every job of the app until it is opened again.
 */
class IpBackstopJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicReference<Job?>(null)

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        running.set(scope.launch {
            try {
                // A job left behind (the switch was turned off while the process was ending, or the data was cleared) ends itself.
                if (!IpStore(app).readSettings().enabled) cancel(app) else IpRunner.onChain(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "backstop run failed", e)
            }
            running.set(null)
            try { jobFinished(params, false) } catch (e: Exception) { Log.w(TAG, "jobFinished failed", e) }
        })
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.getAndSet(null)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FixedIp"
        const val JOB_ID = 7002
        private const val PERIOD_MS = 15 * 60_000L

        /** Schedules the job unless it is already pending (scheduling again would restart its period). Never throws. */
        fun schedule(ctx: Context) {
            try {
                val js = ctx.applicationContext.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) return
                val info = JobInfo.Builder(JOB_ID, ComponentName(ctx.applicationContext, IpBackstopJobService::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build()
                val rc = js.schedule(info)
                Log.i(TAG, "backstop job scheduled: ${if (rc == JobScheduler.RESULT_SUCCESS) "ok" else "refused"}")
            } catch (e: Exception) {
                Log.e(TAG, "backstop job not scheduled", e)
            }
        }

        fun cancel(ctx: Context) {
            try {
                ctx.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            } catch (e: Exception) {
                Log.e(TAG, "backstop job not cancelled", e)
            }
        }
    }
}
