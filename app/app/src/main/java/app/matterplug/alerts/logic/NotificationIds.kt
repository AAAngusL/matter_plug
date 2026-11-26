package app.matterplug.alerts.logic

object NotificationIds {
    /** One notification id per alert kind, so a reminder or a recovery replaces the earlier notification of that kind. */
    fun of(kind: AlertKind): Int = when (kind) {
        AlertKind.NotCharging -> 4201
        AlertKind.PlugUnreachable -> 4202
        AlertKind.GuardDegraded -> 4203
        AlertKind.Status -> 4204
        AlertKind.Test -> 4205
        AlertKind.CellularDown -> 4206
        AlertKind.CellularStatus -> 4207
        AlertKind.IpDrift -> 4208
    }
}
