package info.nightscout.androidaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.interfaces.NsClient
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.sync.nsclient.events.EventNSClientNewLog
import info.nightscout.androidaps.plugins.sync.nsclientV3.NSClientV3Plugin
import info.nightscout.androidaps.receivers.DataWorkerStorage
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.shared.logging.AAPSLogger
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

class LoadTreatmentsWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var context: Context
    @Inject lateinit var nsClientV3Plugin: NSClientV3Plugin
    @Inject lateinit var dateUtil: DateUtil

    companion object {

        val JOB_NAME: String = this::class.java.simpleName
    }

    override fun doWork(): Result {
        var ret = Result.success()

        runBlocking {
            if ((nsClientV3Plugin.lastModified?.collections?.treatments ?: Long.MAX_VALUE) > nsClientV3Plugin.lastFetched.collections.treatments)
                try {
                    val treatments = nsClientV3Plugin.nsAndroidClient.getTreatmentsModifiedSince(nsClientV3Plugin.lastFetched.collections.treatments)
                    aapsLogger.debug("TREATMENTS: $treatments")
                    if (treatments.isNotEmpty()) {
                        rxBus.send(
                            EventNSClientNewLog(
                                "DATA",
                                "received ${treatments.size} treatments starting ${dateUtil.dateAndTimeAndSecondsString(nsClientV3Plugin.lastFetched.collections.treatments)}",
                                NsClient.Version.V3
                            )
                        )
                        // Schedule processing of fetched data and continue of loading
                        WorkManager.getInstance(context)
                            .beginUniqueWork(
                                JOB_NAME,
                                ExistingWorkPolicy.REPLACE,
                                OneTimeWorkRequest.Builder(ProcessTreatmentsWorker::class.java)
                                    .setInputData(dataWorkerStorage.storeInputData(treatments))
                                    .build()
                            ).then(OneTimeWorkRequest.Builder(LoadTreatmentsWorker::class.java).build())
                            .enqueue()
                    } else
                        rxBus.send(
                            EventNSClientNewLog(
                                "DATA", "No treatments starting ${dateUtil.dateAndTimeAndSecondsString(nsClientV3Plugin.lastFetched.collections.treatments)}", NsClient
                                    .Version.V3
                            )
                        )
                } catch (error: Exception) {
                    aapsLogger.error("Error: ", error)
                    ret = Result.failure(workDataOf("Error" to error))
                }
            else
                rxBus.send(EventNSClientNewLog("DATA", "No new treatments starting ${dateUtil.dateAndTimeAndSecondsString(nsClientV3Plugin.lastFetched.collections.treatments)}", NsClient.Version.V3))
        }
        return ret
    }

    init {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
    }
}