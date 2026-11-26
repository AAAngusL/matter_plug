package app.matterplug.alerts

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
 * Delivers the waiting list when a network is available, with the app closed. One persisted job, id 7001,
 * scheduled whenever the list is not empty. Runs steps 1 and 3 of the delivery with a 30-second budget.
 */
class FlushJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicReference<Job?>(null)

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        val job = scope.launch {
            var again = false
            try {
                again = HttpAlerter.flush(AlertOutbox(app), BUDGET_MS)
            } catch (e: CancellationException) {
                throw e      // onStopJob: Android asked us to stop and reschedules by itself; no jobFinished then
            } catch (e: Exception) {
                Log.e(TAG, "flush job failed", e)
                again = true
            }
            running.set(null)
            try {
                jobFinished(params, again)
            } catch (e: Exception) {
                Log.w(TAG, "jobFinished failed", e)
            }
        }
        running.set(job)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.getAndSet(null)?.cancel()
        return true      // reschedule with the default backoff
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Guard"
        const val JOB_ID = 7001
        private const val BUDGET_MS = 30_000L

        /**
         * Schedules the job unless one is already pending or running (scheduling again would cancel a running one).
         * Needs any network, survives a restart. Never throws.
         */
        fun schedule(ctx: Context) {
            try {
                val js = ctx.applicationContext.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) {
                    Log.i(TAG, "flush job already scheduled")
                    return
                }
                val info = JobInfo.Builder(JOB_ID, ComponentName(ctx.applicationContext, FlushJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                val rc = js.schedule(info)
                Log.i(TAG, "flush job scheduled: ${if (rc == JobScheduler.RESULT_SUCCESS) "ok" else "refused"}")
            } catch (e: Exception) {
                Log.e(TAG, "flush job not scheduled", e)
            }
        }

        fun cancel(ctx: Context) {
            try {
                ctx.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            } catch (e: Exception) {
                Log.e(TAG, "flush job not cancelled", e)
            }
        }
    }
}
