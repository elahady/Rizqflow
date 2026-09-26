package com.roziqrizal.rizqflow.billing

import android.content.Context
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tempat nama paket disimpan; dipisah dari SharedPreferences supaya [PurchaseStore] bisa diuji tanpa Android. */
interface PlanStorage {
    fun read(): Set<String>
    fun write(names: Set<String>)
}

private class SharedPrefsPlanStorage(context: Context) : PlanStorage {
    private val prefs = context.applicationContext.getSharedPreferences("purchases", Context.MODE_PRIVATE)

    override fun read(): Set<String> = prefs.getStringSet(KEY_PLANS, emptySet()).orEmpty()

    override fun write(names: Set<String>) {
        prefs.edit().putStringSet(KEY_PLANS, names).apply()
    }

    private companion object {
        const val KEY_PLANS = "owned_plans"
    }
}

/**
 * Paket yang dimiliki perangkat ini, disimpan di SharedPreferences privat aplikasi: pembelian
 * Google Play terikat ke akun Play Store perangkat, bukan ke satu akun ledger lokal (satu perangkat
 * bisa punya banyak akun ledger, S30/S31), jadi statusnya app-wide seperti `ThemePreference`, bukan
 * per akun. `allowBackup` mati, jadi berkas ini tidak ikut cadangan otomatis Android.
 *
 * [grant] dan [revoke] SEMENTARA menandai paket secara lokal tanpa transaksi sungguhan, sampai
 * Google Play Billing terpasang (menyusul di Tahap 7 lanjutan setelah listing Play Console ada,
 * Tahap 8). Satu-satunya pemanggil adalah saklar "Pro (uji)" di menu Lainnya, yang hanya ada di
 * build debug (`BuildConfig.DEBUG`). Bottom sheet S21 sengaja tidak memanggilnya supaya tombol Beli
 * tidak diam-diam membuka Pro tanpa pembayaran sungguhan.
 */
class PurchaseStore(private val storage: PlanStorage) {
    constructor(context: Context) : this(SharedPrefsPlanStorage(context))

    private val _plans = MutableStateFlow(readPlans())
    val plans: StateFlow<Set<Plan>> = _plans.asStateFlow()

    fun grant(plan: Plan) = update(_plans.value + plan)

    fun revoke(plan: Plan) = update(_plans.value - plan)

    private fun update(updated: Set<Plan>) {
        storage.write(updated.map { it.name }.toSet())
        _plans.value = updated
    }

    private fun readPlans(): Set<Plan> =
        storage.read()
            .mapNotNull { raw -> Plan.entries.firstOrNull { it.name == raw } }
            .toSet()
}
