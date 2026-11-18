# Privacy Policy for SilentZone

**Last Updated:** November 17, 2026

SilentZone ("we", "our", or "the app") is designed with a strict offline-first, zero-tracking architecture.

## 1. Information We Do Not Collect
- **Zero Cloud Storage:** SilentZone does not use any external backend servers. All your configured zones, schedules, cell tower IDs, and VIP contacts are stored strictly locally on your device.
- **No GPS Tracking:** The app does not access or record your GPS coordinates.
- **No Data Sharing:** We do not sell, rent, or share any personal information, call logs, or network identifiers with third parties.

## 2. Permissions Used & Why
- **Do Not Disturb (`ACCESS_NOTIFICATION_POLICY`):** Required to automatically enable and disable Silent/DND modes based on your active zones.
- **Wi-Fi & Network State (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`):** Used locally to detect when you connect to or disconnect from Wi-Fi networks.
- **Contacts (`READ_CONTACTS`):** Used solely on-device when you open the VIP Guard contact picker so you can select trusted contacts. Your address book is never uploaded or shared.
- **Phone State & Call Log (`READ_PHONE_STATE`, `READ_CALL_LOG`):** Used strictly locally on-device to identify incoming calls from your selected VIP contacts so emergency calls can ring through silent mode.
- **Coarse Location / Phone State (Optional Cell Tower Mode):** Used only if you explicitly create a Cell Tower zone to read local cellular tower IDs without activating GPS.

## 3. Contact
For any questions regarding this privacy policy, please open an issue on our official GitHub repository: https://github.com/pal039987/silentzone
