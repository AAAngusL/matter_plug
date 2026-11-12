package app.matterplug.guard.logic

/**
 * Makes a settings value valid. Used by GuardStore for everything it reads or writes (G2, BATTERY-GUARD-IMPLEMENTATION 5.1).
 * A field out of range falls back to its default; a pair with high < low + MIN_GAP falls back to the default pair.
 */
fun GuardSettings.coerced(): GuardSettings {
    val d = GuardSettings()
    var l = if (low in 5..50) low else d.low
    var h = if (high in 50..100) high else d.high
    if (h < l + GuardSettings.MIN_GAP) { l = d.low; h = d.high }
    return copy(
        low = l,
        high = h,
        chargingIntervalMin = if (chargingIntervalMin in GuardSettings.INTERVALS) chargingIntervalMin else d.chargingIntervalMin,
        batteryIntervalMin = if (batteryIntervalMin in GuardSettings.INTERVALS) batteryIntervalMin else d.batteryIntervalMin,
    )
}
