<div style="display:flex; flex-direction:column; gap:22px; max-width:560px; margin:0 auto; width:100%;">

    <div><a href="{{ route('denah') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Denah</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Profil</h1>

    <div class="rf-card">
        <div style="font-weight:700; font-size:15px; margin-bottom:4px;">{{ auth()->user()->name }}</div>
        <div style="font-size:13px; color:var(--rf-on-surface-variant);">{{ auth()->user()->email }}</div>
    </div>

    <div class="rf-card" style="border-color:#e3b3ac;">
        <div style="font-weight:700; font-size:15px; margin-bottom:4px;">Hapus akun</div>
        <p style="font-size:13px; color:var(--rf-on-surface-variant); margin:0 0 16px;">
            Menghapus akun ini dari server Rizqflow, termasuk semua ruang, transaksi, dan data lain yang
            tersimpan di sini. Data di aplikasi Android Anda tidak ikut terhapus — ponsel tetap sumber
            kebenaran. Tindakan ini tidak bisa diurungkan.
        </p>
        <button type="button" wire:click="deleteAccount"
            wire:confirm="Hapus akun ini dari server? Semua data di sini (ruang, transaksi, dan lainnya) akan terhapus permanen. Data di aplikasi Android tidak ikut terhapus."
            class="rf-btn" style="background:#b3261e; color:#fff;">
            Hapus akun
        </button>
    </div>

</div>
