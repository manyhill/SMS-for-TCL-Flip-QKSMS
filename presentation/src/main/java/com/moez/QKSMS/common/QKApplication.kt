package com.moez.QKSMS.common

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import androidx.core.provider.FontRequest
import androidx.emoji.text.EmojiCompat
import androidx.emoji.text.FontRequestEmojiCompatConfig
import com.moez.QKSMS.R
import com.moez.QKSMS.common.util.CrashlyticsTree
import com.moez.QKSMS.common.util.FileLoggingTree
import com.moez.QKSMS.common.util.LocalCrashLogger
import com.moez.QKSMS.injection.AppComponentManager
import com.moez.QKSMS.injection.appComponent
import com.moez.QKSMS.manager.AnalyticsManager
import com.moez.QKSMS.manager.BillingManager
import com.moez.QKSMS.manager.ReferralManager
import com.moez.QKSMS.migration.QkMigration
import com.moez.QKSMS.migration.QkRealmMigration
import com.moez.QKSMS.util.NightModeManager
import com.uber.rxdogtag.RxDogTag
import com.uber.rxdogtag.autodispose.AutoDisposeConfigurer
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.HasBroadcastReceiverInjector
import dagger.android.HasServiceInjector
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class QKApplication :
    Application(),
    HasActivityInjector,
    HasBroadcastReceiverInjector,
    HasServiceInjector {

    @Suppress("unused") @Inject lateinit var analyticsManager: AnalyticsManager
    @Suppress("unused") @Inject lateinit var qkMigration: QkMigration

    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var dispatchingActivityInjector: DispatchingAndroidInjector<Activity>
    @Inject lateinit var dispatchingBroadcastReceiverInjector: DispatchingAndroidInjector<BroadcastReceiver>
    @Inject lateinit var dispatchingServiceInjector: DispatchingAndroidInjector<Service>
    @Inject lateinit var fileLoggingTree: FileLoggingTree
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var realmMigration: QkRealmMigration
    @Inject lateinit var referralManager: ReferralManager

    override fun onCreate() {
        super.onCreate()
        LocalCrashLogger.install(this)

        AppComponentManager.init(this)
        runCatching {
            appComponent.inject(this)
        }.onFailure {
            Timber.e(it, "Dagger injection failed")
        }

        configureRealm()

        qkMigration.performMigration()

        GlobalScope.launch(Dispatchers.IO) {
            referralManager.trackReferrer()
            billingManager.checkForPurchases()
            billingManager.queryProducts()
        }

        nightModeManager.updateCurrentTheme()

        val fontRequest = FontRequest(
            "com.google.android.gms.fonts",
            "com.google.android.gms",
            "Noto Color Emoji Compat",
            R.array.com_google_android_gms_fonts_certs
        )
        EmojiCompat.init(FontRequestEmojiCompatConfig(this, fontRequest))

        Timber.plant(Timber.DebugTree(), CrashlyticsTree(), fileLoggingTree)

        RxDogTag.builder().configureWith(AutoDisposeConfigurer::configure).install()
    }

    private fun configureRealm() {
        Realm.init(this)

        var realmDir = File(noBackupFilesDir, "realm")
        if (!realmDir.exists()) realmDir.mkdirs()

        runCatching {
            File(realmDir, ".rw_probe").apply {
                writeText("ok")
                delete()
            }
        }.onFailure {
            Timber.w(it, "Realm dir not writable; switching to fallback")
            realmDir = File(noBackupFilesDir, "realm_fallback").apply { mkdirs() }
        }

        val config = RealmConfiguration.Builder()
            .name("${packageName}.realm")
            .directory(realmDir)
            .compactOnLaunch()
            .migration(realmMigration)
            .schemaVersion(QkRealmMigration.SchemaVersion)
            .build()

        Realm.setDefaultConfiguration(config)

        Timber.d("Realm default path = ${config.path}")
    }

    override fun activityInjector(): AndroidInjector<Activity> = dispatchingActivityInjector
    override fun broadcastReceiverInjector(): AndroidInjector<BroadcastReceiver> = dispatchingBroadcastReceiverInjector
    override fun serviceInjector(): AndroidInjector<Service> = dispatchingServiceInjector
}
