package info.nightscout.androidaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.R
import info.nightscout.androidaps.interfaces.NsClient
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.source.NSClientSourcePlugin
import info.nightscout.androidaps.plugins.sync.nsclient.events.EventNSClientNewLog
import info.nightscout.androidaps.plugins.sync.nsclientV3.NSClientV3Plugin
import info.nightscout.androidaps.receivers.DataWorkerStorage
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.sharedPreferences.SP
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

class LoadBgWorker(
    context: Context, params: WorkerParameters
) : Worker(context, params) {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var sp: SP
    @Inject lateinit var context: Context
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var nsClientV3Plugin: NSClientV3Plugin

    companion object {

        val JOB_NAME: String = this::class.java.simpleName
    }

    override fun doWork(): Result {
        var ret = Result.success()

        runBlocking {
            if ((nsClientV3Plugin.lastModified?.collections?.entries ?: Long.MAX_VALUE) > nsClientV3Plugin.lastFetched.collections.entries)
                try {
                    //val sgvs = nsClientV3Plugin.nsAndroidClient.getSgvsModifiedSince(nsClientV3Plugin.lastFetched.collections.entries)
                    val sgvs = nsClientV3Plugin.nsAndroidClient.getSgvsNewerThan(nsClientV3Plugin.lastFetched.collections.entries, 500)
                    aapsLogger.debug("SGVS: $sgvs")
                    if (sgvs.isNotEmpty()) {
                        rxBus.send(
                            EventNSClientNewLog(
                                "DATA",
                                "received ${sgvs.size} sgvs starting ${dateUtil.dateAndTimeAndSecondsString(nsClientV3Plugin.lastFetched.collections.entries)}",
                                NsClient.Version.V3
                            )
                        )
                        // Objective0
                        sp.putBoolean(R.string.key_ObjectivesbgIsAvailableInNS, true)
                        // Schedule processing of fetched data and continue of loading
                        WorkManager.getInstance(context).beginUniqueWork(
                            JOB_NAME,
                            ExistingWorkPolicy.REPLACE,
                            OneTimeWorkRequest.Builder(NSClientSourcePlugin.NSClientSourceWorker::class.java).setInputData(dataWorkerStorage.storeInputData(sgvs)).build()
                        ).then(OneTimeWorkRequest.Builder(LoadBgWorker::class.java).build()).enqueue()
                    } else rxBus.send(EventNSClientNewLog("DATA", "No sgvs starting ${dateUtil.dateAndTimeAndSecondsString(nsClientV3Plugin.lastFetched.collections.entries)}", NsClient.Version.V3))
                } catch (error: Exception) {
                    aapsLogger.error("Error: ", error)
                    ret = Result.failure(workDataOf("Error" to error))
                }
            else rxBus.send(EventNSClientNewLog("DATA", "No new sgvs starting ${dateUtil.dateAndTimeAndSecondsString(nsClientV3Plugin.lastFetched.collections.entries)}", NsClient.Version.V3))
        }
        return ret
    }

    init {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
    }
}