package com.shubham.silentzone.engine

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.shubham.silentzone.data.ZoneRepository

class WifiWakeJobService : JobService() {

    companion object {
        private const val JOB_ID = 7071

        // Minimum gap before the job may run again. Without it the job is immediately runnable again
        // while Wi-Fi is connected -> endless job -> evaluate -> reschedule -> job loop.
        private const val RERUN_DELAY_MS = 60_000L

        /**
         * @param force true = (re)schedule even if a job is already pending (used from onStartJob).
         * Normal callers (evaluateNow) must NOT reschedule an existing job every time.
         */
        fun scheduleWifiWake(context: Context, force: Boolean = false) {
            val repo = ZoneRepository(context)
            val hasWifiRules = repo.getRules().any { it.isEnabled && it.isWifiTriggerActive() }
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (!hasWifiRules) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (!force && scheduler.getPendingJob(JOB_ID) != null) return

            val component = ComponentName(context, WifiWakeJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setMinimumLatency(RERUN_DELAY_MS)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(jobInfo) }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        // Evaluation is asynchronous; finish the job only when it is done
        ZoneEvaluatorEngine.evaluateNow(applicationContext) {
            // Finish first, then re-arm (scheduling while still running would stop this very job).
            // The re-armed job has a delay, so there is no hot loop.
            jobFinished(params, false)
            scheduleWifiWake(applicationContext, force = true)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Ask the system to retry later with its own back-off instead of rescheduling by hand
        return true
    }
}
