package com.roziqrizal.rizqflow.ui.recovery

import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.LocalBackupHandle
import com.roziqrizal.rizqflow.domain.sync.PreRestoreCopyInfo
import com.roziqrizal.rizqflow.domain.sync.RevertEligibility
import com.roziqrizal.rizqflow.domain.sync.RevertPreparation
import com.roziqrizal.rizqflow.domain.sync.RevertResult
import com.roziqrizal.rizqflow.domain.sync.SqliteDatabaseCheck
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pesan layar "Kembalikan data sebelum pemulihan": setiap kegagalan punya kalimat sendiri yang menegaskan data tidak berubah. */
class PreRestoreRevertMessagesTest {
    @Test
    fun `salinan yang tidak layak dijelaskan menurut sebabnya`() {
        assertEquals(
            R.string.prerestore_unusable_newer,
            RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.SCHEMA_TOO_NEW).messageRes(),
        )
        for (problem in listOf(SqliteDatabaseCheck.Problem.NOT_SQLITE, SqliteDatabaseCheck.Problem.TRUNCATED, SqliteDatabaseCheck.Problem.EMPTY_SCHEMA)) {
            assertEquals(R.string.prerestore_unusable_corrupt, RevertEligibility.Unusable(problem).messageRes(), problem.name)
        }
    }

    @Test
    fun `setiap kegagalan persiapan punya pesan, dan siap pakai tidak punya`() {
        assertEquals(R.string.prerestore_busy, RevertPreparation.Busy.failureRes())
        assertEquals(R.string.prerestore_unreadable, RevertPreparation.CopyUnreadable.failureRes())
        assertEquals(R.string.prerestore_backup_failed, RevertPreparation.EmergencyBackupFailed.failureRes())
        assertEquals(R.string.prerestore_gone, RevertPreparation.NotEligible(RevertEligibility.NoCopy).failureRes())
        assertEquals(
            R.string.prerestore_unusable_newer,
            RevertPreparation.NotEligible(RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.SCHEMA_TOO_NEW)).failureRes(),
        )
        assertEquals(
            R.string.prerestore_gone,
            RevertPreparation.NotEligible(RevertEligibility.Available(PreRestoreCopyInfo(1, 1))).failureRes(),
            "tidak terjadi, tetapi tetap ada jawaban aman",
        )
    }

    @Test
    fun `hasil akhir punya pesan sendiri`() {
        val handle = LocalBackupHandle("/x/akun.db.prerevert", 1, 1)
        assertEquals(R.string.prerestore_done, RevertResult.Reverted(handle).messageRes())
        assertEquals(R.string.prerestore_gone, RevertResult.CopyGone.messageRes())
        assertEquals(R.string.prerestore_gone, RevertResult.AlreadyUsed.messageRes())
        assertEquals(R.string.prerestore_apply_failed, RevertResult.ApplyFailed("IOException").messageRes())
    }
}
