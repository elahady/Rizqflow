package com.roziqrizal.rizqflow.ui.recovery

import androidx.annotation.StringRes
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.RevertEligibility
import com.roziqrizal.rizqflow.domain.sync.RevertPreparation
import com.roziqrizal.rizqflow.domain.sync.RevertResult
import com.roziqrizal.rizqflow.domain.sync.SqliteDatabaseCheck

/**
 * Pesan layar "Kembalikan data sebelum pemulihan". Setiap kegagalan menegaskan keadaan data (tidak ada yang berubah) dan
 * memberi jalan berikutnya; tidak ada galat teknis. Dipisah dari layar supaya bisa diuji tanpa Compose.
 */
@StringRes
fun RevertEligibility.Unusable.messageRes(): Int = when (reason) {
    SqliteDatabaseCheck.Problem.SCHEMA_TOO_NEW -> R.string.prerestore_unusable_newer
    SqliteDatabaseCheck.Problem.NOT_SQLITE,
    SqliteDatabaseCheck.Problem.TRUNCATED,
    SqliteDatabaseCheck.Problem.EMPTY_SCHEMA,
    -> R.string.prerestore_unusable_corrupt
}

/** Pesan untuk persiapan yang tidak menghasilkan [RevertPreparation.Ready]; null untuk [RevertPreparation.Ready]. */
@StringRes
fun RevertPreparation.failureRes(): Int? = when (this) {
    is RevertPreparation.Ready -> null
    RevertPreparation.Busy -> R.string.prerestore_busy
    is RevertPreparation.NotEligible -> when (val why = why) {
        RevertEligibility.NoCopy -> R.string.prerestore_gone
        is RevertEligibility.Unusable -> why.messageRes()
        is RevertEligibility.Available -> R.string.prerestore_gone // tidak terjadi; aman jika terjadi
    }
    RevertPreparation.CopyUnreadable -> R.string.prerestore_unreadable
    RevertPreparation.EmergencyBackupFailed -> R.string.prerestore_backup_failed
}

/** Pesan akhir setelah konfirmasi. [RevertResult.Reverted] berarti penggantian berkas sudah diserahkan. */
@StringRes
fun RevertResult.messageRes(): Int = when (this) {
    is RevertResult.Reverted -> R.string.prerestore_done
    RevertResult.CopyGone, RevertResult.AlreadyUsed -> R.string.prerestore_gone
    is RevertResult.ApplyFailed -> R.string.prerestore_apply_failed
}
