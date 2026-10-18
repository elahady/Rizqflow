<?php

namespace App\Support;

/**
 * Fitur yang bisa terkunci, dan paket yang membukanya (lihat docs/monetisasi.md, "Titik
 * penguncian"). Keamanan, ekspor data, backup lokal, dan dasar zakat tidak ada di sini karena
 * selalu gratis. Porting dari Feature di :domain (domain/.../entitlement/Entitlements.kt).
 */
enum Feature: string
{
    case UnlimitedRooms = 'unlimited_rooms';
    case AdvancedAllocationRules = 'advanced_allocation_rules';
    case RoleSystems = 'role_systems';
    case MultiZakatProfile = 'multi_zakat_profile';
    case AutomaticGoldPrice = 'automatic_gold_price';
    case ReportsAndInsights = 'reports_and_insights';
    case MultiCurrency = 'multi_currency';
    case HomeWidget = 'home_widget';
    case Themes = 'themes';
    case NotificationCapture = 'notification_capture';
    case EncryptedSync = 'encrypted_sync';
    case SharedFamilyRoom = 'shared_family_room';

    public function requiredPlan(): Plan
    {
        return match ($this) {
            self::EncryptedSync, self::SharedFamilyRoom => Plan::Sync,
            default => Plan::Pro,
        };
    }
}
