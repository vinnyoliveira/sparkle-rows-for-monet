package com.ptvin.sparklerows

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ptvin.sparklerows.data.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Atualiza a grelha. O agendamento normal e feito a hora de fim do programa
 * atual (scheduleAt); o periodico e so rede de seguranca.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { Sync.run(applicationContext) }
        Result.success()
    }

    companion object {
        private const val PERIODIC = "iptv-sync"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Rede de seguranca: se algum agendamento falhar, isto recupera. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Corre quando o programa atual termina (e reagenda-se a si mesmo). */
        fun scheduleAt(context: Context, atMillis: Long?) {
            if (atMillis == null) return
            val delay = (atMillis - System.currentTimeMillis() + 30_000).coerceAtLeast(60_000)
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints())
                .build()
            // Nome unico por hora alvo: nunca cancelamos o worker que esta a correr.
            val name = "iptv-boundary-$atMillis"
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        }
    }
}
