package com.moez.QKSMS.service

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.moez.QKSMS.common.util.extensions.jobScheduler
import com.moez.QKSMS.manager.PermissionManager
import com.moez.QKSMS.repository.BackupRepository
import com.moez.QKSMS.util.Preferences
import dagger.android.AndroidInjection
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AutoBackupService : JobService() {

    companion object {
        private const val JobId = 8120236

        @SuppressLint("MissingPermission") // Added in [presentation]'s AndroidManifest.xml
        fun scheduleJob(context: Context, days: Int) {
            if (days <= 0) {
                cancelJob(context)
                return
            }

            Timber.i("Scheduling automatic backup job every $days days")
            val serviceComponent = ComponentName(context, AutoBackupService::class.java)
            val periodicJob = JobInfo.Builder(JobId, serviceComponent)
                .setPeriodic(TimeUnit.DAYS.toMillis(days.toLong()))
                .setPersisted(true)
                .build()

            context.jobScheduler.schedule(periodicJob)
        }

        fun cancelJob(context: Context) {
            Timber.i("Canceling automatic backup job")
            context.jobScheduler.cancel(JobId)
        }
    }

    @Inject lateinit var backupRepo: BackupRepository
    @Inject lateinit var permissionManager: PermissionManager
    @Inject lateinit var prefs: Preferences

    private var disposable: Disposable? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        AndroidInjection.inject(this)

        val days = prefs.autoBackup.get()
        if (days <= 0 || !permissionManager.hasStorage()) {
            jobFinished(params, false)
            return false
        }

        disposable = io.reactivex.Observable.just(Unit)
            .subscribeOn(Schedulers.io())
            .doOnNext {
                backupRepo.performBackup()
                prefs.lastAutoBackup.set(System.currentTimeMillis())
            }
            .subscribe({
                jobFinished(params, false)
            }, { error ->
                Timber.w(error)
                jobFinished(params, true)
            })

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        disposable?.dispose()
        return true
    }
}
