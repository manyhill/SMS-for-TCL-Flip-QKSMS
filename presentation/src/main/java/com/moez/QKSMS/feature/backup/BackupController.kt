/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.feature.backup

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Environment
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import com.jakewharton.rxbinding2.view.clicks
import com.moez.QKSMS.R
import com.moez.QKSMS.common.base.QkController
import com.moez.QKSMS.common.util.DateFormatter
import com.moez.QKSMS.common.util.extensions.getLabel
import com.moez.QKSMS.common.util.extensions.setBackgroundTint
import com.moez.QKSMS.common.util.extensions.setPositiveButton
import com.moez.QKSMS.common.util.extensions.setTint
import com.moez.QKSMS.common.widget.PreferenceView
import com.moez.QKSMS.injection.appComponent
import com.moez.QKSMS.model.BackupFile
import com.moez.QKSMS.repository.BackupRepository
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.backup_controller.*
import kotlinx.android.synthetic.main.backup_list_dialog.view.*
import kotlinx.android.synthetic.main.preference_view.view.*
import javax.inject.Inject

class BackupController : QkController<BackupView, BackupState, BackupPresenter>(), BackupView {

    companion object {
        private const val BackupLocationRequestCode = 4901
    }

    @Inject lateinit var adapter: BackupAdapter
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject override lateinit var presenter: BackupPresenter

    private val activityVisibleSubject: Subject<Unit> = PublishSubject.create()
    private val confirmRestoreSubject: Subject<Unit> = PublishSubject.create()
    private val stopRestoreSubject: Subject<Unit> = PublishSubject.create()
    private val autoBackupSubject: Subject<Int> = PublishSubject.create()
    private val backupLocationSubject: Subject<String> = PublishSubject.create()
    private var lastFocusedBackupViewId: Int = View.NO_ID

    private val defaultBackupDirectory: String
        get() = Environment.getExternalStorageDirectory().toString() + activity!!.getString(R.string.backup_location_default)

    private val backupFilesDialog by lazy {
        val view = View.inflate(activity, R.layout.backup_list_dialog, null)
                .apply { files.adapter = adapter.apply { emptyView = empty } }

        AlertDialog.Builder(activity!!)
                .setView(view)
                .setCancelable(true)
                .create()
                .apply { setOnDismissListener { restoreBackupFocus() } }
    }

    private val confirmRestoreDialog by lazy {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.backup_restore_confirm_title)
                .setMessage(R.string.backup_restore_confirm_message)
                .setPositiveButton(R.string.backup_restore_title, confirmRestoreSubject)
                .setNegativeButton(R.string.button_cancel, null)
                .create()
                .apply { setOnDismissListener { restoreBackupFocus() } }
    }

    private val stopRestoreDialog by lazy {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.backup_restore_stop_title)
                .setMessage(R.string.backup_restore_stop_message)
                .setPositiveButton(R.string.button_stop, stopRestoreSubject)
                .setNegativeButton(R.string.button_cancel, null)
                .create()
                .apply { setOnDismissListener { restoreBackupFocus() } }
    }

    init {
        appComponent.inject(this)
        layoutRes = R.layout.backup_controller
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.backup_title)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()

        themedActivity?.colors?.theme()?.let { theme ->
            progressBar.indeterminateTintList = ColorStateList.valueOf(theme.theme)
            progressBar.progressTintList = ColorStateList.valueOf(theme.theme)
            fab.setBackgroundTint(theme.theme)
            fabIcon.setTint(theme.textPrimary)
            fabLabel.setTextColor(theme.textPrimary)
        }

        // Make the list titles bold
        linearLayout.children
                .mapNotNull { it as? PreferenceView }
                .map { it.titleView }
                .forEach { it.setTypeface(it.typeface, Typeface.BOLD) }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        activityVisibleSubject.onNext(Unit)
    }

    override fun render(state: BackupState) {
        when {
            state.backupProgress.running -> {
                progressIcon.setImageResource(R.drawable.ic_file_upload_black_24dp)
                progressTitle.setText(R.string.backup_backing_up)
                progressSummary.text = state.backupProgress.getLabel(activity!!)
                progressSummary.isVisible = progressSummary.text.isNotEmpty()
                progressCancel.isVisible = false
                val running = (state.backupProgress as? BackupRepository.Progress.Running)
                progressBar.isVisible = state.backupProgress.indeterminate || running?.max ?: 0 > 0
                progressBar.isIndeterminate = state.backupProgress.indeterminate
                progressBar.max = running?.max ?: 0
                progressBar.progress = running?.count ?: 0
                progress.isVisible = true
                fab.isVisible = false
            }

            state.restoreProgress.running -> {
                progressIcon.setImageResource(R.drawable.ic_file_download_black_24dp)
                progressTitle.setText(R.string.backup_restoring)
                progressSummary.text = state.restoreProgress.getLabel(activity!!)
                progressSummary.isVisible = progressSummary.text.isNotEmpty()
                progressCancel.isVisible = true
                val running = (state.restoreProgress as? BackupRepository.Progress.Running)
                progressBar.isVisible = state.restoreProgress.indeterminate || running?.max ?: 0 > 0
                progressBar.isIndeterminate = state.restoreProgress.indeterminate
                progressBar.max = running?.max ?: 0
                progressBar.progress = running?.count ?: 0
                progress.isVisible = true
                fab.isVisible = false
            }

            else -> {
                progress.isVisible = false
                fab.isVisible = true
            }
        }

        backup.summary = state.lastBackup
        autoBackup.summary = when (state.autoBackupDays) {
            0 -> activity!!.getString(R.string.backup_auto_never)
            else -> activity!!.getString(R.string.backup_auto_summary, state.autoBackupDays)
        }
        backupLocation.summary = when {
            state.backupDirectory.isBlank() -> defaultBackupDirectory
            state.backupDirectory.startsWith("content://") -> activity!!.getString(R.string.backup_location_selected)
            else -> state.backupDirectory
        }

        adapter.data = state.backups

        fabIcon.setImageResource(when (state.upgraded) {
            true -> R.drawable.ic_file_upload_black_24dp
            false -> R.drawable.ic_star_black_24dp
        })

        fabLabel.setText(when (state.upgraded) {
            true -> R.string.backup_now
            false -> R.string.title_qksms_plus
        })
    }

    override fun activityVisible(): Observable<*> = activityVisibleSubject

    override fun restoreClicks(): Observable<*> = restore.clicks()

    override fun restoreFileSelected(): Observable<BackupFile> = adapter.backupSelected
            .doOnNext { backupFilesDialog.dismiss() }

    override fun restoreConfirmed(): Observable<*> = confirmRestoreSubject

    override fun stopRestoreClicks(): Observable<*> = progressCancel.clicks()

    override fun stopRestoreConfirmed(): Observable<*> = stopRestoreSubject

    override fun fabClicks(): Observable<*> = fab.clicks()

    override fun autoBackupClicks(): Observable<*> = autoBackup.clicks()

    override fun autoBackupChanged(): Observable<Int> = autoBackupSubject

    override fun backupLocationClicks(): Observable<*> = backupLocation.clicks()

    override fun backupLocationChanged(): Observable<String> = backupLocationSubject

    override fun requestStoragePermission() {
        ActivityCompat.requestPermissions(activity!!, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
    }

    override fun selectFile() {
        rememberBackupFocus(restore)
        backupFilesDialog.show()
    }

    override fun confirmRestore() {
        rememberBackupFocus()
        confirmRestoreDialog.show()
    }

    override fun stopRestore() {
        rememberBackupFocus(progressCancel)
        stopRestoreDialog.show()
    }

    override fun showAutoBackupDialog(days: Int) {
        rememberBackupFocus(autoBackup)
        val picker = NumberPicker(activity).apply {
            minValue = 0
            maxValue = 30
            value = days.coerceIn(minValue, maxValue)
            displayedValues = Array(maxValue - minValue + 1) { index ->
                when (index) {
                    0 -> activity!!.getString(R.string.backup_auto_never)
                    else -> index.toString()
                }
            }
            wrapSelectorWheel = false
        }

        val label = TextView(activity).apply {
            text = activity!!.getString(R.string.backup_auto_picker_days)
            gravity = android.view.Gravity.CENTER
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(picker)
            addView(label)
        }

        AlertDialog.Builder(activity!!)
            .setTitle(R.string.backup_auto_dialog_title)
            .setMessage(R.string.backup_auto_dialog_message)
            .setView(layout)
            .setPositiveButton(R.string.button_set) { _, _ ->
                autoBackupSubject.onNext(picker.value)
            }
            .setNegativeButton(R.string.backup_auto_never) { _, _ ->
                autoBackupSubject.onNext(0)
            }
            .show()
            .setOnDismissListener { restoreBackupFocus() }
    }

    override fun selectBackupLocation() {
        rememberBackupFocus(backupLocation)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }

        val currentActivity = activity ?: return
        if (intent.resolveActivity(currentActivity.packageManager) == null) {
            Toast.makeText(currentActivity, R.string.backup_location_picker_unavailable, Toast.LENGTH_LONG).show()
            restoreBackupFocus()
            return
        }

        try {
            startActivityForResult(intent, BackupLocationRequestCode)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(currentActivity, R.string.backup_location_picker_unavailable, Toast.LENGTH_LONG).show()
            restoreBackupFocus()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == BackupLocationRequestCode && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )

            try {
                activity?.contentResolver?.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                Toast.makeText(activity, R.string.backup_location_permission_failed, Toast.LENGTH_LONG).show()
                restoreBackupFocus()
                return
            }
            backupLocationSubject.onNext(uri.toString())
            backupLocation.post { restoreBackupFocus() }
        }
    }

    private fun rememberBackupFocus(fallback: View? = null) {
        val focusedView = activity?.currentFocus
        lastFocusedBackupViewId = fallback
            ?.id
            ?.takeIf { it != View.NO_ID }
            ?: focusedView?.id?.takeIf { it != View.NO_ID }
            ?: View.NO_ID
    }

    private fun restoreBackupFocus() {
        val viewId = lastFocusedBackupViewId.takeIf { it != View.NO_ID } ?: return
        containerView?.post {
            containerView?.findViewById<View>(viewId)
                ?.takeIf { it.isShown && !it.isFocused }
                ?.requestFocus()
        }
    }

}
