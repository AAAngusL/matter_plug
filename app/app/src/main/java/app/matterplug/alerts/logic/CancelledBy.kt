package app.matterplug.alerts.logic

/** Which switch closed an alert without a recovery (RECOVERY-SIGNALS.md 2.4). [cause] starts the cancelled message. */
enum class CancelledBy(val wire: String, val cause: String) {
    GuardOff("guard_off", "Battery Guard turned off"),
    AlertsOff("alerts_off", "Battery Guard alerts turned off"),
    CellularOff("cellular_off", "Cellular Check turned off"),
    CellularAlertsOff("cellular_alerts_off", "Cellular alerts turned off"),
    IpOff("ip_off", "Fixed IP turned off"),
    IpUnpinned("ip_unpinned", "Fixed IP network unpinned"),
}
