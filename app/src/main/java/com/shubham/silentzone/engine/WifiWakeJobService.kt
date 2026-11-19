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

        fun scheduleWifiWake(context: Context) {
            val repo = ZoneRepository(context)
            val hasWifiRules = repo.getRules().any { it.isEnabled && it.isWifiTriggerActive() }
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (!hasWifiRules) {
                scheduler.cancel(JOB_ID)
                return
            }
            val component = ComponentName(context, WifiWakeJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(jobInfo) }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        ZoneEvaluatorEngine.evaluateNow(applicationContext)
        // Re-schedule so future Wi-Fi connections also wake the app
        scheduleWifiWake(applicationContext)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        scheduleWifiWake(applicationContext)
        return true
    }
}
