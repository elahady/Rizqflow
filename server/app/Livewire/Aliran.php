<?php

namespace App\Livewire;

use App\Models\SavedView;
use App\Models\User;
use App\Support\Feature;
use App\Support\Period;
use App\Support\Reports\AliranReport;
use Illuminate\Contracts\View\View;
use Livewire\Attributes\Url;
use Livewire\Component;

/**
 * Analisa › Aliran (Pro): diagram Sankey rezeki → ruang → pos dan sisa untuk satu bulan atau
 * 3/12 bulan yang berakhir di bulan terpilih (docs/design/web-mockup/project/Analisa.dc.html).
 *
 * Semua angka dihitung di App\Support\Reports\AliranReport (dipakai juga oleh unduhan PDF), lalu
 * ditata oleh SankeyLayout. Tidak ada state berat di properti publik: semuanya dihitung ulang di render().
 *
 * Filter yang bisa disimpan sebagai tampilan (App\Models\SavedView): rentang, bulan, akun
 * ('semua' atau 'kustom' dengan daftar id), dan ruang yang disorot. Semua aksi yang menulis atau
 * memakai tampilan tersimpan hanya berjalan untuk pengguna Pro, sama dengan halaman ini.
 */
class Aliran extends Component
{
    public const RANGES = ['1', '3', '12'];

    /** Batas tampilan tersimpan per pengguna untuk halaman ini. */
    public const MAX_TAMPILAN = 12;

    private const PAGE = 'aliran';

    #[Url]
    public ?string $bulan = null;

    /** Lebar rentang dalam bulan, berakhir di bulan terpilih: '1', '3', atau '12'. */
    #[Url]
    public string $rentang = '1';

    /** Filter akun: 'semua' (bawaan, tanpa filter) atau 'kustom' (pilih beberapa akun di $akunIds). */
    #[Url(as: 'akun')]
    public string $akun = 'semua';

    /** Id akun yang dipilih dipisah koma; hanya dipakai saat $akun 'kustom'. */
    #[Url(as: 'ids')]
    public string $akunIds = '';

    /** Ruang yang disorot di diagram: 'semua' atau 'r{id}'. */
    #[Url(as: 'sorot')]
    public string $sorot = 'semua';

    /** Nama untuk tampilan yang akan disimpan. */
    public string $namaTampilan = '';

    public function setRentang(string $value): void
    {
        $this->rentang = in_array($value, self::RANGES, true) ? $value : '1';
    }

    public function setAkun(string $value): void
    {
        if ($value !== 'kustom') {
            $this->akun = 'semua';
            $this->akunIds = '';

            return;
        }

        $this->akun = 'kustom';
        if (trim($this->akunIds) === '') {
            // Pertama kali memilih "Kustom", semua akun terpilih dulu supaya pengguna tinggal mengurangi.
            $this->akunIds = implode(',', auth()->user()->accounts()->pluck('id')->map(fn ($id) => (int) $id)->all());
        }
    }

    /** Menambah atau mengeluarkan satu akun dari pilihan kustom. Satu akun tidak boleh dikeluarkan sendirian. */
    public function toggleAkun(int $id): void
    {
        if ($this->akun !== 'kustom') {
            return;
        }

        $user = auth()->user();
        // Daftar dibersihkan dulu (hanya id milik pengguna), jadi yang tersimpan di URL selalu sah.
        $terpilih = AliranReport::accountIds($user, 'kustom', $this->akunIds);
        $milikku = $user->accounts()->pluck('id')->map(fn ($v) => (int) $v)->all();

        if (in_array($id, $terpilih, true)) {
            $terpilih = count($terpilih) === 1 ? $terpilih : array_values(array_diff($terpilih, [$id]));
        } elseif (in_array($id, $milikku, true)) {
            $terpilih[] = $id;
        }

        sort($terpilih);
        $this->akunIds = implode(',', $terpilih);
    }

    /** Simpan kombinasi filter saat ini sebagai tampilan. $sorot dikirim dari diagram (fokus ruang yang aktif). */
    public function simpanTampilan(string $sorot): void
    {
        if (! $this->bolehPakai()) {
            return;
        }

        $this->sorot = $sorot;
        $this->namaTampilan = trim($this->namaTampilan);
        $this->validate(
            ['namaTampilan' => ['required', 'string', 'max:60']],
            [
                'namaTampilan.required' => 'Beri nama dulu untuk tampilan ini.',
                'namaTampilan.max' => 'Nama tampilan maksimal 60 karakter.',
            ],
        );

        $user = auth()->user();
        $jumlah = SavedView::query()->where('user_id', $user->id)->where('page', self::PAGE)->count();
        if ($jumlah >= self::MAX_TAMPILAN) {
            $this->addError('namaTampilan', 'Sudah ada '.self::MAX_TAMPILAN.' tampilan tersimpan. Hapus salah satu dulu.');

            return;
        }

        SavedView::create([
            'user_id' => $user->id,
            'page' => self::PAGE,
            'name' => $this->namaTampilan,
            'filters' => $this->filterSaatIni($user),
        ]);
        $this->namaTampilan = '';
    }

    /** Pasang kembali filter sebuah tampilan tersimpan. Nilainya divalidasi ulang sebelum dipakai. */
    public function terapkanTampilan(int $id): void
    {
        if (! $this->bolehPakai()) {
            return;
        }

        $view = $this->tampilanMilikku($id);
        if ($view === null) {
            return;
        }

        $filters = $view->filters ?? [];
        $this->setRentang((string) ($filters['rentang'] ?? '1'));
        $this->bulan = isset($filters['bulan']) && is_string($filters['bulan']) && $filters['bulan'] !== ''
            ? Period::query(Period::fromQuery($filters['bulan']))
            : null;
        $this->akun = ($filters['akun'] ?? '') === 'kustom' ? 'kustom' : 'semua';
        $this->akunIds = $this->akun === 'kustom'
            ? implode(',', array_map('intval', (array) ($filters['ids'] ?? [])))
            : '';
        $this->sorot = (string) ($filters['sorot'] ?? 'semua');
    }

    public function hapusTampilan(int $id): void
    {
        if (! $this->bolehPakai()) {
            return;
        }

        $this->tampilanMilikku($id)?->delete();
    }

    public function render(): View
    {
        $user = auth()->user();
        $end = Period::fromQuery($this->bulan);
        $count = $this->monthCount();
        $canUseReports = $this->bolehPakai();
        $akunIds = AliranReport::accountIds($user, $this->akun, $this->akunIds);
        $akunMode = $this->akun === 'kustom' ? 'kustom' : 'semua';

        return view('livewire.aliran', [
            'end' => $end,
            'count' => $count,
            'rangeLabel' => AliranReport::rangeLabel($end, $count),
            'canUseReports' => $canUseReports,
            'flow' => $canUseReports ? AliranReport::build($user, $end, $count, $akunIds) : null,
            'sorotAktif' => $this->sorotAktif($user),
            'akunMode' => $akunMode,
            'akunList' => $user->accounts()->orderBy('sort_order')->orderBy('id')->get(),
            'akunTerpilih' => $akunIds ?? [],
            'akunLabel' => AliranReport::accountLabel($user, $akunIds),
            'tampilan' => $canUseReports
                ? SavedView::query()->where('user_id', $user->id)->where('page', self::PAGE)->orderBy('id')->get()
                : collect(),
            'maxTampilan' => self::MAX_TAMPILAN,
            'pdfUrl' => route('laporan.aliran.pdf', array_filter([
                'rentang' => $count,
                'bulan' => Period::query($end),
                'akun' => $akunMode,
                'ids' => $akunMode === 'kustom' ? implode(',', $akunIds ?? []) : null,
            ], fn ($value) => $value !== null)),
        ])->layout('components.layout', ['title' => 'Analisa — Rizqflow', 'period' => $end]);
    }

    private function monthCount(): int
    {
        return in_array($this->rentang, self::RANGES, true) ? (int) $this->rentang : 1;
    }

    private function bolehPakai(): bool
    {
        return auth()->user()?->entitlements()->isEnabled(Feature::ReportsAndInsights) ?? false;
    }

    /**
     * Ruang yang disorot, divalidasi terhadap ruang aktif pengguna. Fokus yang tidak dikenal (ruang
     * diarsipkan atau dihapus) jatuh ke 'semua'.
     */
    private function sorotAktif(User $user): string
    {
        $valid = ['semua'];
        foreach ($user->rooms()->orderBy('sort_order')->orderBy('id')->get() as $room) {
            if (! $room->archived) {
                $valid[] = 'r'.$room->id;
            }
        }

        return in_array($this->sorot, $valid, true) ? $this->sorot : 'semua';
    }

    /** @return array{rentang: string, bulan: ?string, akun: string, ids: list<int>, sorot: string} */
    private function filterSaatIni(User $user): array
    {
        $kustom = $this->akun === 'kustom';

        return [
            'rentang' => (string) $this->monthCount(),
            'bulan' => trim((string) $this->bulan) === '' ? null : Period::query(Period::fromQuery($this->bulan)),
            'akun' => $kustom ? 'kustom' : 'semua',
            'ids' => $kustom ? AliranReport::accountIds($user, 'kustom', $this->akunIds) : [],
            'sorot' => $this->sorotAktif($user),
        ];
    }

    private function tampilanMilikku(int $id): ?SavedView
    {
        return SavedView::query()
            ->where('user_id', auth()->id())
            ->where('page', self::PAGE)
            ->find($id);
    }
}
