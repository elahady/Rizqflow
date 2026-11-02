package com.roziqrizal.rizqflow.ui.ruangkeluarga

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.ledger.AmountPad
import com.roziqrizal.rizqflow.domain.ledger.EnvelopeRules
import com.roziqrizal.rizqflow.domain.ledger.MemberSpend
import com.roziqrizal.rizqflow.domain.ledger.RoomDetailLoader
import com.roziqrizal.rizqflow.domain.ledger.RoomHistoryStats
import com.roziqrizal.rizqflow.domain.ledger.RoomStatus
import com.roziqrizal.rizqflow.domain.ledger.toEnvelopeMonth
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.LeaveRoomOutcome
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.IssuedSecretBinder
import com.roziqrizal.rizqflow.domain.sync.KeyGrantPlanner
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import com.roziqrizal.rizqflow.domain.sync.RoomPermissions
import com.roziqrizal.rizqflow.domain.sync.SharedMemberCache
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import com.roziqrizal.rizqflow.domain.sync.isRateLimited
import com.roziqrizal.rizqflow.domain.sync.ownRole
import com.roziqrizal.rizqflow.domain.sync.removableMembers
import com.roziqrizal.rizqflow.sync.DeviceId
import com.roziqrizal.rizqflow.sync.isUnauthorized
import com.roziqrizal.rizqflow.sync.retryOnceIfRotated
import com.roziqrizal.rizqflow.sync.sessionEndedBy
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.UiMessage
import com.roziqrizal.rizqflow.ui.denah.statusIcon
import com.roziqrizal.rizqflow.ui.denah.statusTint
import com.roziqrizal.rizqflow.ui.familyRoomMessage
import com.roziqrizal.rizqflow.ui.formatDate
import com.roziqrizal.rizqflow.ui.formatMonth
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.onboarding.AmountKeypad
import com.roziqrizal.rizqflow.ui.paywall.opensSyncPaywall
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Keputusan menunggu untuk dialog "riwayat ruang ikut dibagikan?" (keputusan pemilik #4, sync-desain.md bagian J):
 * [role] disimpan supaya [applyServerRole][com.roziqrizal.rizqflow.data.sync.SharedRoomActivation.applyServerRole]
 * tetap dipanggil dengan peran yang benar setelah pengguna memilih, bukan hanya saat ruang baru pertama dibagikan.
 */
private data class HistoryShareConfirm(val role: SharedRoomRole, val stats: RoomHistoryStats)

/**
 * Kelola ruang keluarga (Tahap 9, roadmap.md "Ruang keluarga bersama"): daftar anggota, undang
 * lewat QR, dan tombol sinkron manual. Membuka layar ini sekaligus memastikan kunci ruang ada dan
 * keanggotaan sendiri terdaftar (idempoten) — tidak ada langkah "mulai berbagi" terpisah, karena
 * membuat kunci/mendaftar diri sendiri tidak membocorkan apa pun (pemilik sudah punya semua
 * datanya). Trigger sync di putaran ini hanya manual, bukan latar belakang (konsisten "tenang,
 * bukan panik").
 *
 * Layar ini butuh langganan Sync (Tahap 9, "Penguncian Sync di klien"): tanpa Sync, atau bila server menjawab 403,
 * [onOpenSyncPaywall] dibuka dan layar ditutup lewat [onClose]; tidak ada request yang dijalankan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuangKeluargaScreen(
    workspace: AccountWorkspace,
    roomId: RoomId,
    notifier: Notifier,
    onClose: () -> Unit,
    onOpenSyncPaywall: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    // Paket Sync tidak dimiliki (atau server menolak 403): paywall Sync dibuka, layar ditutup, bukan dibiarkan gagal diam-diam.
    fun leaveForSyncPaywall() {
        onOpenSyncPaywall()
        onClose()
    }

    var roomName by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<RoomMemberInfo>?>(null) }
    // Pesan lembut bila daftar anggota gagal dimuat (jaringan, 429, bukan anggota); null = tidak ada galat.
    var loadError by remember { mutableStateOf<UiMessage?>(null) }
    var signedIn by remember { mutableStateOf(true) }
    var sessionEnded by remember { mutableStateOf(false) }
    var inviteSheet by rememberSaveable { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // Keluarkan anggota (Tahap 9, keputusan pemilik #1: hanya pemilik): pilih anggota, cabut keanggotaannya di server,
    // lalu rotasi kunci dan QR ulang untuk anggota yang tetap — lihat RoomMemberKeyRedistribution.
    var removeSheet by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<RoomMemberInfo?>(null) }
    var removing by remember { mutableStateOf(false) }
    // Pindah kepemilikan (Tahap 9, hanya pemilik): pilih anggota, konfirmasi, lalu muat ulang. Tidak merotasi kunci dan
    // tidak membuat QR baru: pemilik lama tetap anggota dengan kunci yang sama. Peran lokal ikut berubah lewat loadMembers.
    var transferSheet by remember { mutableStateOf(false) }
    var transferTarget by remember { mutableStateOf<RoomMemberInfo?>(null) }
    var transferring by remember { mutableStateOf(false) }
    var rotationEpoch by remember { mutableIntStateOf(0) }
    var justRotated by remember { mutableStateOf(false) }
    // Keluar dari ruang (anggota non-pemilik): konfirmasi jujur, cabut keanggotaan di server, tandai LEFT di perangkat.
    // `left` = perangkat ini sudah keluar (status LEFT): tidak ada permintaan ke server dan tidak ada join otomatis.
    var leaveConfirm by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    // Dialog "riwayat ruang ikut dibagikan?" (keputusan pemilik #4): hanya tampil saat ruang baru pertama kali
    // diaktifkan oleh PEMILIK (lihat activateRoom). null = tidak ada keputusan menunggu.
    var historyConfirm by remember { mutableStateOf<HistoryShareConfirm?>(null) }
    // Peran perangkat ini MENURUT SERVER (bendera owner di daftar anggota), bukan siapa yang membuka layar.
    val isOwner = members?.ownRole() == SharedRoomRole.OWNER
    val canManageMembers = RoomPermissions.canRemoveMembers(members?.ownRole())
    val removable = members?.removableMembers().orEmpty()
    // Rincian terpakai per anggota (peran-anggota.md D.3, H langkah 5): murni turunan transaksi, belum amplop.
    // Kosong = belum dimuat ATAU ruang belum pernah dipakai bulan ini; keduanya tampil sama (bagian disembunyikan).
    var memberSpend by remember { mutableStateOf<List<MemberSpend>>(emptyList()) }
    // Jatah yang dialirkan dari rezeki SAYA ke ruang ini bulan ini (D.5, baris info di sheet "Atur amplop").
    var myAllocated by remember { mutableStateOf(Money.zero()) }
    // Amplop bulan ini (D, H langkah 8): null sebelum pernah dimuat; lihat loadEnvelopeSummary.
    var envelopeSummary by remember { mutableStateOf<EnvelopeRules.RoomSummary?>(null) }
    val canManageEnvelope = RoomPermissions.canManageEnvelope(members?.ownRole())
    var envelopeSheet by remember { mutableStateOf(false) }
    // Digit mentah (AmountPad) per anggota sedang diedit di sheet "Atur amplop"; selected = baris yang menerima papan angka.
    var envelopeDigits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var envelopeSelected by remember { mutableStateOf<String?>(null) }
    var savingEnvelope by remember { mutableStateOf(false) }

    suspend fun loadMemberSpend() {
        val detail = RoomDetailLoader(workspace.repositories.rooms, workspace.repositories.transactions, workspace.repositories.accounts)
            .load(roomId, YearMonth.now(), LocalDate.now())
        memberSpend = detail?.memberSpend.orEmpty()
        myAllocated = detail?.card?.allocated ?: Money.zero()
    }

    /**
     * Ringkasan amplop bulan ini (D.3): anggota aktif menurut cache `shared_member` ([replaceAll] di [loadMembers]),
     * terpakai per anggota dari [memberSpend] (penulis null = saya, dipetakan ke [SharedMemberCache.isMe]).
     */
    suspend fun loadEnvelopeSummary() {
        val cached = workspace.repositories.family.all(roomId)
        if (cached.isEmpty()) {
            envelopeSummary = null
            return
        }
        val myId = cached.firstOrNull { it.isMe }?.memberId
        val activeIds = cached.filter { it.status == SharedRoomStatus.ACTIVE }.map { it.memberId }.toSet()
        val spentByMemberId = memberSpend.mapNotNull { spend ->
            val author = spend.author
            val id = if (author == null) myId else author.takeIf { it.isNotEmpty() }
            id?.let { it to spend.spent }
        }.toMap()
        val entries = workspace.envelopes.entries(roomId)
        val room = workspace.repositories.rooms.find(roomId)
        val month = YearMonth.now()
        envelopeSummary = EnvelopeRules.summarize(
            month = month.toEnvelopeMonth(),
            entries = entries,
            activeMemberIds = activeIds,
            spentByMember = spentByMemberId,
            totalSpentRoom = workspace.repositories.transactions.roomTotals(month.atDay(1), month.atEndOfMonth()).spent[roomId] ?: Money.zero(),
            roomKind = room?.kind ?: RoomKind.MENCUKUPI,
        )
    }

    fun memberLabel(memberId: String?): String = when {
        memberId == null -> resources.getString(R.string.family_member_spend_unknown)
        memberId == members?.firstOrNull { it.you }?.memberId -> resources.getString(R.string.family_member_spend_me)
        else -> members?.firstOrNull { it.memberId == memberId }?.let { it.name ?: it.email }
            ?: resources.getString(R.string.family_member_spend_other, memberId.take(6))
    }

    fun openEnvelopeSheet() {
        val activeIds = members.orEmpty().mapNotNull { it.memberId }.toSet()
        val currentEntries = envelopeSummary?.members?.associate { it.memberId to it.envelope.minor }.orEmpty()
        envelopeDigits = activeIds.associateWith { id -> currentEntries[id]?.takeIf { it > 0 }?.toString() ?: "" }
        envelopeSelected = members.orEmpty().firstOrNull { it.you }?.memberId ?: activeIds.firstOrNull()
        envelopeSheet = true
    }

    fun saveEnvelope() {
        savingEnvelope = true
        scope.launch {
            val month = YearMonth.now().toEnvelopeMonth()
            for ((memberId, digits) in envelopeDigits) {
                workspace.envelopes.setEnvelope(roomId, memberId, month, AmountPad.toRupiah(digits).minor)
            }
            loadEnvelopeSummary()
            savingEnvelope = false
            envelopeSheet = false
        }
    }

    /**
     * Mengaktifkan ruang dengan [role] dari server, menunda keputusan "riwayat ikut dibagikan?" lewat dialog bila ruang
     * BELUM ACTIVE (keputusan pemilik #4) — satu-satunya jalur yang mungkin punya riwayat lokal sebelum dibagi, karena
     * jalur anggota baru (FamilyRoomJoin.markActive) sudah menandai ACTIVE lebih dulu dan tidak punya riwayat ruang itu
     * sama sekali. Ruang yang sudah ACTIVE memanggil applyServerRole langsung, tanpa dialog ulang (sekadar sinkron
     * peran). Ruang kosong (0 transaksi buatan sendiri) diaktifkan langsung juga: tidak ada riwayat untuk dipilih.
     */
    suspend fun activateRoom(role: SharedRoomRole) {
        if (workspace.sharedRoomActivation.statusOf(roomId) == SharedRoomStatus.ACTIVE) {
            workspace.sharedRoomActivation.applyServerRole(roomId, role)
            return
        }
        val stats = workspace.repositories.transactions.historyStats(roomId)
        if (stats == null) {
            workspace.sharedRoomActivation.applyServerRole(roomId, role)
        } else {
            historyConfirm = HistoryShareConfirm(role, stats)
        }
    }

    suspend fun loadMembers() {
        val session = workspace.serverSession
        // 401 padahal token sementara itu diputar (permintaan membawa token lama): diulang sekali dengan token baru,
        // bukan dinyatakan "sesi berakhir" (lihat retryOnceIfRotated). null = tidak ada token (belum masuk).
        val result = session.retryOnceIfRotated(
            isUnauthorized = { it?.isUnauthorized() == true },
            call = {
                val token = session.token()
                if (token == null) {
                    null
                } else {
                    // Idempoten: memastikan diri sendiri terdaftar sebelum menampilkan daftar, supaya anggota
                    // yang baru bergabung lewat QR langsung terlihat tanpa harus menekan Sinkronkan dulu.
                    workspace.roomMembershipRemote.join(token, roomId.value)
                    workspace.roomMembershipRemote.members(token, roomId.value)
                }
            },
        )
        if (result == null) {
            signedIn = false
            return
        }
        signedIn = true
        when (result) {
            is RemoteResult.Success -> {
                members = result.value
                loadError = null
                // Pemilik: ikat rahasia undangan ke anggota yang memakainya (hash di daftar anggota cocok dengan rahasia
                // terbitan). Murni lokal dan idempoten; anggota biasa tidak punya rahasia terbitan, jadi tidak ada efeknya.
                IssuedSecretBinder(workspace.memberSecretStore).bind(roomId, result.value)
                // Penandaan ACTIVE menyalakan sinkron otomatis untuk ruang ini. Perannya diambil dari bendera owner
                // server (pembuat ruang = pemilik, keputusan pemilik #1) dan menimpa peran lokal yang berbeda — dulu
                // siapa pun yang membuka layar ini tercatat OWNER tanpa syarat.
                result.value.ownRole()?.let { activateRoom(it) }
                // Cache lokal (shared_member, peran-anggota.md E.2/H langkah 8): dipakai EnvelopeSyncHandler menegakkan
                // D.4 dan layar ini sendiri menampilkan nama. Anggota tanpa memberId (server lama) tidak ikut dicache.
                workspace.repositories.family.replaceAll(
                    roomId,
                    result.value.mapNotNull { member ->
                        member.memberId?.let { id ->
                            SharedMemberCache(
                                memberId = id,
                                label = member.name ?: member.email,
                                role = if (member.owner) SharedRoomRole.OWNER else SharedRoomRole.MEMBER,
                                status = SharedRoomStatus.ACTIVE,
                                isMe = member.you,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        }
                    },
                )
                loadEnvelopeSummary()
            }

            // "Sesi berakhir" hanya dinyatakan bila ServerSession benar-benar menyatakannya (token sudah dibersihkan dan
            // "perlu sambungkan ulang" naik); 401 lain yang sesinya masih sah diperlakukan sebagai gagal muat biasa.
            is RemoteResult.Failure -> when {
                result.error.opensSyncPaywall() -> leaveForSyncPaywall()
                session.sessionEndedBy(result) -> {
                    signedIn = false
                    sessionEnded = true
                }

                // 429 dan galat berkode mendapat pesannya sendiri (bukan "gagal memuat" generik).
                else -> loadError = result.error.familyRoomMessage(R.string.family_members_error)
            }
        }
    }

    LaunchedEffect(roomId, reloadKey) {
        // Murni lokal (tidak butuh jaringan atau status LEFT/Sync): dimuat tanpa syarat, termasuk saat sudah keluar
        // ruang (data historis bulan ini tetap ada di perangkat).
        loadMemberSpend()
        // Sudah keluar dari ruang ini (status LEFT): tidak ada request apa pun, terutama bukan join otomatis di bawah,
        // yang akan mendaftarkan perangkat ini kembali. Bergabung lagi hanya lewat undangan baru.
        if (workspace.sharedRoomActivation.statusOf(roomId) == SharedRoomStatus.LEFT) {
            roomName = workspace.repositories.rooms.find(roomId)?.name
            left = true
            return@LaunchedEffect
        }
        // Dicek sebelum request apa pun (join, anggota): tanpa Sync, tidak ada yang dikirim ke server — KECUALI
        // perangkat ini sudah tercatat ACTIVE di ruang ini (keputusan pemilik I-8, peran-anggota.md): anggota
        // yang bergabung lewat Sync PEMILIK ruang tidak punya Sync sendiri, tapi server sudah mengonfirmasinya
        // sebelumnya (join sewaktu itu berhasil). Server tetap menegakkan ulang di tiap panggilan (sync_required
        // bila Sync pemilik kemudian berakhir, dipetakan ke paywall yang sama lewat opensSyncPaywall()).
        val alreadyActiveMember = workspace.sharedRoomActivation.statusOf(roomId) == SharedRoomStatus.ACTIVE
        if (!workspace.entitlements.isEnabled(Feature.SHARED_FAMILY_ROOM) && !alreadyActiveMember) {
            leaveForSyncPaywall()
            return@LaunchedEffect
        }
        roomName = workspace.repositories.rooms.find(roomId)?.name
        workspace.roomKeyProvider.getOrCreateRoomKey(roomId) // kunci wajib ada sebelum QR bisa dirender
        loadMembers()
    }

    fun syncNow() {
        syncing = true
        scope.launch {
            // Dicek sebelum request: paket yang sudah turun di tengah sesi tidak boleh menjalankan sinkron —
            // kecuali anggota ini sudah ACTIVE di ruang ini lewat Sync pemilik (keputusan pemilik I-8): sync
            // ruang ini (join + syncSharedRoomNow di bawah) tidak menyentuh Sync pribadi.
            if (!workspace.entitlements.isEnabled(Feature.ENCRYPTED_SYNC) &&
                workspace.sharedRoomActivation.statusOf(roomId) != SharedRoomStatus.ACTIVE
            ) {
                leaveForSyncPaywall()
                syncing = false
                return@launch
            }
            val session = workspace.serverSession
            val deviceId = DeviceId.read(context)
            // Sama seperti loadMembers: 401 karena token baru saja diputar diulang sekali, bukan "sesi berakhir".
            val result = session.retryOnceIfRotated(
                isUnauthorized = { it is SyncResult.Failed && it.error == RemoteError.Unauthorized },
                call = {
                    val token = session.token()
                    if (token == null) {
                        SyncResult.NotSignedIn
                    } else {
                        workspace.roomMembershipRemote.join(token, roomId.value)
                        // Kunci ruang yang sama dengan sinkron otomatis: tidak pernah berjalan bersamaan untuk ruang ini.
                        workspace.syncSharedRoomNow(roomId, deviceId)
                    }
                },
            )
            when (result) {
                is SyncResult.Success -> {
                    notifier.show(resources.getString(R.string.family_sync_result, result.pushed, result.applied))
                    reloadKey++
                }

                is SyncResult.Failed -> when {
                    session.sessionEndedBy(result.error) -> {
                        signedIn = false
                        sessionEnded = true
                    }

                    result.error.opensSyncPaywall() -> leaveForSyncPaywall()
                    // 429 ("terlalu sering, coba lagi sebentar") dan galat berkode punya pesan sendiri; tidak ada
                    // coba ulang otomatis di sini: pengguna yang menekan tombolnya lagi.
                    else -> notifier.show(result.error.familyRoomMessage(R.string.family_sync_failed).resolve(resources))
                }

                // Server bilang perangkat ini sudah bukan anggota ruang ini (ditemukan selama sinkron, bukan tombol
                // "Keluar"). Bukan paywall: SyncCoordinator sudah menandai ruang REMOVED di perangkat ini.
                SyncResult.RemovedFromRoom -> notifier.show(resources.getString(R.string.family_removed_from_room))

                SyncResult.NotSignedIn -> signedIn = false
            }
            syncing = false
        }
    }

    fun removeMember(member: RoomMemberInfo) {
        val userId = member.userId ?: return
        if (!workspace.entitlements.isEnabled(Feature.SHARED_FAMILY_ROOM)) {
            leaveForSyncPaywall()
            return
        }
        removing = true
        scope.launch {
            val session = workspace.serverSession
            val result = session.retryOnceIfRotated(
                isUnauthorized = { it?.isUnauthorized() == true },
                call = { session.token()?.let { token -> workspace.roomMembershipRemote.remove(token, roomId.value, userId) } },
            )
            when (result) {
                null -> signedIn = false
                is RemoteResult.Success -> {
                    // Keanggotaan sudah dicabut di server; kunci ruang dirotasi dan QR baru disiapkan untuk anggota yang tetap.
                    workspace.memberRemoval.rotateAndPrepareInvite(roomId, roomName.orEmpty())
                    // Distribusi kunci baru (key_grant langkah 4-6): anggota yang tetap dan punya rahasia terikat menerima keyset lewat
                    // log ruang; sisanya (QR lama, atau undangan belum terikat) harus memindai QR baru. Bila penerbitan gagal, semua
                    // anggota yang tetap jatuh ke jalur QR ini.
                    val remaining = members.orEmpty().filter { !it.you && it.userId != userId }
                    val plan = KeyGrantPlanner.plan(roomId, remaining, workspace.memberSecretStore)
                    val published = if (plan.secrets.isEmpty()) null else workspace.publishKeyGrants(roomId, DeviceId.read(context), plan.secrets)
                    val delivered = if (published is RemoteResult.Success) plan.covered.size else 0
                    val needQr = remaining.size - delivered
                    if (needQr > 0) {
                        rotationEpoch++ // memaksa QR di bawah dibangun ulang dari kunci yang baru dirotasi
                        justRotated = true
                        inviteSheet = true
                    }
                    val who = member.name ?: member.email
                    notifier.show(
                        when {
                            remaining.isEmpty() -> resources.getString(R.string.family_remove_done_alone, who)
                            needQr == 0 -> resources.getString(R.string.family_remove_done_auto, who, delivered)
                            delivered == 0 -> resources.getString(R.string.family_remove_done, who)
                            else -> resources.getString(R.string.family_remove_done_auto_qr, who, delivered, needQr)
                        },
                    )
                    reloadKey++
                }

                is RemoteResult.Failure -> when {
                    result.error.opensSyncPaywall() -> leaveForSyncPaywall()
                    session.sessionEndedBy(result) -> {
                        signedIn = false
                        sessionEnded = true
                    }

                    else -> {
                        notifier.show(result.error.familyRoomMessage(R.string.family_remove_failed).resolve(resources))
                        // Daftar mungkin basi (anggota sudah keluar, atau peran berubah) — kecuali bila server minta
                        // menunggu (429): memuat ulang sekarang hanya menambah permintaan.
                        if (!result.error.isRateLimited()) reloadKey++
                    }
                }
            }
            removing = false
        }
    }

    fun transferOwnership(member: RoomMemberInfo) {
        val userId = member.userId ?: return
        if (!workspace.entitlements.isEnabled(Feature.SHARED_FAMILY_ROOM)) {
            leaveForSyncPaywall()
            return
        }
        transferring = true
        scope.launch {
            val session = workspace.serverSession
            val result = session.retryOnceIfRotated(
                isUnauthorized = { it?.isUnauthorized() == true },
                call = { session.token()?.let { token -> workspace.roomMembershipRemote.transferOwnership(token, roomId.value, userId) } },
            )
            when (result) {
                null -> signedIn = false
                is RemoteResult.Success -> {
                    notifier.show(resources.getString(R.string.family_transfer_done, member.name ?: member.email))
                    // Peran kita sendiri sudah berubah di server: muat ulang supaya tombol pemilik hilang dan peran lokal
                    // ikut (loadMembers -> activateRoom).
                    reloadKey++
                }

                is RemoteResult.Failure -> when {
                    result.error.opensSyncPaywall() -> leaveForSyncPaywall()
                    session.sessionEndedBy(result) -> {
                        signedIn = false
                        sessionEnded = true
                    }

                    else -> {
                        notifier.show(result.error.familyRoomMessage(R.string.family_transfer_failed).resolve(resources))
                        // Peran atau daftar mungkin basi (mis. pemilik sudah berganti di perangkat lain); muat ulang kecuali 429.
                        if (!result.error.isRateLimited()) reloadKey++
                    }
                }
            }
            transferring = false
        }
    }

    fun leaveRoom() {
        leaving = true
        scope.launch {
            val session = workspace.serverSession
            // Leave tidak butuh Sync di server, jadi tidak digerbang entitlement dan tidak pernah membuka paywall.
            // Sama seperti panggilan lain: 401 karena token baru saja diputar diulang sekali, bukan "sesi berakhir".
            val outcome = session.retryOnceIfRotated(
                isUnauthorized = { it is LeaveRoomOutcome.Failed && it.error == RemoteError.Unauthorized },
                call = { session.token()?.let { token -> workspace.sharedRoomLeaver.leave(token, roomId) } },
            )
            when (outcome) {
                null -> signedIn = false
                LeaveRoomOutcome.Left, LeaveRoomOutcome.NoLongerMember -> {
                    left = true
                    notifier.show(resources.getString(R.string.family_leave_done))
                }

                LeaveRoomOutcome.OwnerCannotLeave -> {
                    notifier.show(resources.getString(R.string.family_err_owner_cannot_leave))
                    reloadKey++ // peran lokal mungkin basi: muat ulang agar tombol hilang dan catatan pemilik tampil
                }

                is LeaveRoomOutcome.Failed -> when {
                    session.sessionEndedBy(outcome.error) -> {
                        signedIn = false
                        sessionEnded = true
                    }

                    else -> notifier.show(outcome.error.familyRoomMessage(R.string.family_leave_failed).resolve(resources))
                }
            }
            leaving = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.family_title), style = MaterialTheme.typography.headlineMedium)
            roomName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = spacing.s1))
            }

            if (left) {
                Text(
                    stringResource(R.string.family_left_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s4),
                )
            } else if (!signedIn) {
                Text(
                    stringResource(if (sessionEnded) R.string.family_session_ended else R.string.family_not_signed_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s4),
                )
            } else {
                Text(stringResource(R.string.family_members_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2))
                when {
                    loadError != null -> {
                        Text(loadError!!.resolve(resources), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { scope.launch { loadMembers() } }, modifier = Modifier.padding(top = spacing.s1)) {
                            Text(stringResource(R.string.family_retry))
                        }
                    }

                    members == null -> CircularProgressIndicator(modifier = Modifier.padding(top = spacing.s2))

                    else -> members!!.forEach { member ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s2)) {
                            Text(
                                member.name?.let { "$it (${member.email})" } ?: member.email,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(if (member.owner) R.string.family_members_owner else R.string.family_members_pencatat),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = spacing.s2),
                            )
                            if (member.you) {
                                Text(stringResource(R.string.family_members_you), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }

                // Rincian terpakai per anggota (peran-anggota.md D.3, H langkah 5): hanya tampil bila ruang ini
                // sudah pernah dipakai lebih dari satu anggota bulan ini (satu baris saja = tidak berguna ditampilkan).
                if (memberSpend.size > 1) {
                    Text(
                        stringResource(R.string.family_member_spend_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2),
                    )
                    memberSpend.forEach { spend ->
                        val author = spend.author
                        val label = when {
                            author == null -> stringResource(R.string.family_member_spend_me)
                            author.isEmpty() -> stringResource(R.string.family_member_spend_unknown)
                            else -> members?.firstOrNull { it.memberId == author }?.let { it.name ?: it.email }
                                ?: stringResource(R.string.family_member_spend_other, author.take(6))
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(formatRupiah(spend.spent), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Amplop bulanan (peran-anggota.md D, H langkah 8): jatah, terpakai, dan progres per anggota.
                // "Perlu perhatian" ikon+teks (bukan warna saja, D.6); tombol "Atur amplop" hanya untuk Pemilik.
                Text(
                    stringResource(R.string.family_envelope_section_title, formatMonth(YearMonth.now())),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2),
                )
                val summary = envelopeSummary
                if (summary == null || !summary.hasAnyEnvelope) {
                    Text(stringResource(R.string.family_envelope_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.family_envelope_allocated, formatRupiah(summary.totalEnvelope)), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.family_envelope_spent, formatRupiah(summary.totalSpentRoom)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    summary.members.filter { it.hasEnvelope }.forEach { m ->
                        Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.s2)) {
                            Text(memberLabel(m.memberId), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                if (m.remaining.isNegative) {
                                    stringResource(R.string.denah_over, formatRupiah(-m.remaining.minor))
                                } else {
                                    "${formatRupiah(m.spent)} / ${formatRupiah(m.envelope)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val color = if (m.status == RoomStatus.PERLU_PERHATIAN) MaterialTheme.rizqflow.statusWarning else MaterialTheme.colorScheme.primary
                        LinearProgressIndicator(
                            progress = { (m.progressBp ?: 0).coerceIn(0, 10_000) / 10_000f },
                            modifier = Modifier.padding(top = spacing.s1).fillMaxWidth().height(8.dp),
                            color = color,
                            trackColor = color.copy(alpha = 0.2f),
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                        if (m.status == RoomStatus.PERLU_PERHATIAN) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s1), modifier = Modifier.padding(top = spacing.s1)) {
                                Icon(statusIcon(m.status), contentDescription = null, tint = statusTint(m.status), modifier = Modifier.size(16.dp))
                                Text(
                                    if (m.remaining.isNegative) {
                                        stringResource(R.string.denah_over, formatRupiah(-m.remaining.minor))
                                    } else {
                                        stringResource(R.string.denah_remaining, formatRupiah(m.remaining))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (canManageEnvelope) {
                    OutlinedButton(
                        onClick = ::openEnvelopeSheet,
                        modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.family_envelope_action)) }
                }

                // Keputusan pemilik #1 (sync-desain.md bagian J): hanya pemilik ruang yang mengundang dan mengeluarkan.
                if (RoomPermissions.canInvite(members?.ownRole())) {
                    Button(
                        onClick = { inviteSheet = true },
                        modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.family_invite_action)) }
                }
                OutlinedButton(
                    onClick = ::syncNow,
                    enabled = !syncing,
                    modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.family_sync_action)) }
                // Keluarkan anggota (Tahap 9, server-sync.md "Mengeluarkan anggota"): pilih anggota, cabut
                // keanggotaannya di server (DELETE /rooms/{roomId}/members/{userId}), lalu kunci baru dan QR ulang.
                if (canManageMembers && removable.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { removeSheet = true },
                        enabled = !removing,
                        modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.family_remove_action)) }
                    // Pindah kepemilikan ke anggota lain (pemilik lama lalu boleh keluar). Dibatasi ke anggota yang sama
                    // dengan daftar "Keluarkan anggota": diri sendiri dan pemilik tidak pernah ikut.
                    OutlinedButton(
                        onClick = { transferSheet = true },
                        enabled = !transferring,
                        modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.family_transfer_action)) }
                }
                // Keluar dari ruang: hanya anggota biasa (peran MENURUT SERVER). Pemilik tidak bisa keluar (server menolak
                // owner_cannot_leave), jadi alih-alih tombol yang pasti gagal ia mendapat penjelasan.
                if (RoomPermissions.canLeave(members?.ownRole())) {
                    OutlinedButton(
                        onClick = { leaveConfirm = true },
                        enabled = !leaving,
                        modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.family_leave_action)) }
                } else if (isOwner) {
                    Text(
                        stringResource(R.string.family_owner_cannot_leave_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s4),
                    )
                }
            }
        }
    }

    if (leaveConfirm) {
        AlertDialog(
            onDismissRequest = { leaveConfirm = false },
            title = { Text(stringResource(R.string.family_leave_confirm_title)) },
            text = { Text(stringResource(R.string.family_leave_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    leaveConfirm = false
                    leaveRoom()
                }) { Text(stringResource(R.string.family_leave_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { leaveConfirm = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }

    historyConfirm?.let { pending ->
        val today = remember { LocalDate.now() }
        AlertDialog(
            onDismissRequest = { historyConfirm = null },
            title = { Text(stringResource(R.string.family_history_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.family_history_confirm_body,
                        pending.stats.count,
                        formatDate(pending.stats.oldest, today),
                        formatDate(pending.stats.newest, today),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    historyConfirm = null
                    scope.launch { workspace.sharedRoomActivation.applyServerRole(roomId, pending.role) }
                }) { Text(stringResource(R.string.family_history_confirm_share_all)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    historyConfirm = null
                    // Awal hari ini di zona waktu perangkat, dikodekan sebagai milidetik UTC (konvensi yang sama
                    // dengan CatatFlow.toPickerMillis): transaksi dengan tanggal transaksi SEBELUM hari ini tidak ikut.
                    val startOfToday = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    scope.launch { workspace.sharedRoomActivation.applyServerRole(roomId, pending.role, historyStartMillis = startOfToday) }
                }) { Text(stringResource(R.string.family_history_confirm_from_today)) }
            },
        )
    }

    if (removeSheet) {
        ModalBottomSheet(onDismissRequest = { removeSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.family_remove_pick_title), style = MaterialTheme.typography.titleLarge)
                removable.forEach { member ->
                    Text(
                        member.name?.let { "$it (${member.email})" } ?: member.email,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                removeSheet = false
                                removeTarget = member
                            }
                            .padding(vertical = 14.dp),
                    )
                    HorizontalDivider()
                }
                TextButton(onClick = { removeSheet = false }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.catat_date_cancel))
                }
            }
        }
    }

    removeTarget?.let { target ->
        val label = target.name ?: target.email
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.family_remove_confirm_title, label)) },
            text = { Text(stringResource(R.string.family_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    removeMember(target)
                }) { Text(stringResource(R.string.family_remove_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }

    if (transferSheet) {
        ModalBottomSheet(onDismissRequest = { transferSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.family_transfer_pick_title), style = MaterialTheme.typography.titleLarge)
                removable.forEach { member ->
                    Text(
                        member.name?.let { "$it (${member.email})" } ?: member.email,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                transferSheet = false
                                transferTarget = member
                            }
                            .padding(vertical = 14.dp),
                    )
                    HorizontalDivider()
                }
                TextButton(onClick = { transferSheet = false }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.catat_date_cancel))
                }
            }
        }
    }

    transferTarget?.let { target ->
        val label = target.name ?: target.email
        AlertDialog(
            onDismissRequest = { transferTarget = null },
            title = { Text(stringResource(R.string.family_transfer_confirm_title, label)) },
            text = { Text(stringResource(R.string.family_transfer_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    transferTarget = null
                    transferOwnership(target)
                }) { Text(stringResource(R.string.family_transfer_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { transferTarget = null }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }

    if (inviteSheet) {
        ModalBottomSheet(onDismissRequest = { inviteSheet = false; justRotated = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.family_invite_sheet_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(if (justRotated) R.string.family_rotate_key_sheet_hint else R.string.family_invite_sheet_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // rotationEpoch di kunci remember: QR ini membaca kunci ruang TERKINI (exportRoomKey), jadi
                // wajib dibangun ulang setelah rotasi walau roomId/roomName tidak berubah.
                // Undangan terdaftar (key_grant langkah 2): QR baru dengan rahasia segar didaftarkan ke server lebih
                // dulu (hanya hash-nya), baru dirender. Sekali pakai, 7 hari; tiap sheet dibuka = undangan baru.
                var inviteQr by remember(roomId, roomName, rotationEpoch) { mutableStateOf<InviteQrResult?>(null) }
                LaunchedEffect(roomId, roomName, rotationEpoch) {
                    inviteQr = buildInviteQr(workspace, roomId, roomName.orEmpty())
                }
                when (val qr = inviteQr) {
                    null -> {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                        Text(
                            stringResource(R.string.family_invite_preparing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    is InviteQrResult.Ready -> {
                        Image(bitmap = qr.bitmap, contentDescription = null, modifier = Modifier.padding(top = 16.dp).size(240.dp))
                        Text(
                            stringResource(R.string.family_invite_valid_days),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    InviteQrResult.NotSignedIn -> Text(
                        stringResource(R.string.family_join_not_signed_in),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )

                    is InviteQrResult.Failed -> Text(
                        qr.message.resolve(resources),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                TextButton(onClick = { inviteSheet = false; justRotated = false }, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.catat_date_cancel))
                }
            }
        }
    }

    // Sheet "Atur amplop" (peran-anggota.md F, H langkah 8): hanya Pemilik (tombolnya saja yang disembunyikan untuk
    // anggota lain — penegakan sungguhan ada di EnvelopeSyncHandler.apply, D.4). Nominal lewat papan angka yang sama
    // dengan Catat (AmountKeypad); baris info "lebih besar dari yang dialirkan" hanya informasi, tidak menahan Simpan.
    if (envelopeSheet) {
        ModalBottomSheet(onDismissRequest = { envelopeSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.envelope_sheet_title, roomName.orEmpty()), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.envelope_sheet_subtitle, formatMonth(YearMonth.now())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s1, bottom = spacing.s3),
                )

                members.orEmpty().forEach memberLoop@{ member ->
                    val id = member.memberId ?: return@memberLoop
                    val digits = envelopeDigits[id].orEmpty()
                    val selected = envelopeSelected == id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { envelopeSelected = id }
                            .padding(vertical = spacing.s2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            member.name?.let { "$it${if (member.you) " (${stringResource(R.string.family_member_spend_me)})" else ""}" } ?: member.email,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatRupiah(AmountPad.toRupiah(digits)),
                            style = if (selected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider()
                }

                val totalMinor = envelopeDigits.values.sumOf { AmountPad.toRupiah(it).minor }
                Text(
                    stringResource(R.string.envelope_sheet_total, formatRupiah(totalMinor)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.s4),
                )
                Text(
                    stringResource(R.string.envelope_sheet_allocated_hint, roomName.orEmpty(), formatRupiah(myAllocated)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s1),
                )
                // D.5: info saja, tidak pernah menahan tombol Simpan.
                if (totalMinor > myAllocated.minor) {
                    Text(
                        stringResource(R.string.envelope_sheet_over_hint, formatRupiah(totalMinor - myAllocated.minor)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s2),
                    )
                }

                envelopeSelected?.let { sel ->
                    AmountKeypad(
                        enabled = !savingEnvelope,
                        onKey = { key -> envelopeDigits = envelopeDigits + (sel to AmountPad.apply(envelopeDigits[sel].orEmpty(), key)) },
                        modifier = Modifier.padding(top = spacing.s4),
                    )
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.s4), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { envelopeSheet = false }, enabled = !savingEnvelope) { Text(stringResource(R.string.catat_date_cancel)) }
                    Button(onClick = ::saveEnvelope, enabled = !savingEnvelope, modifier = Modifier.padding(start = spacing.s2)) {
                        Text(stringResource(R.string.envelope_sheet_save))
                    }
                }
            }
        }
    }
}
