package io.github.jenil1320.cellspan.data

import android.content.Intent

/**
 * The phone's own charge limit — the only thing on a stock device that actually stops a
 * charge at a set level.
 *
 * No Android API lets an app cap charging. The kernel node that does it
 * (`/sys/class/power_supply/battery/charge_limit` on a Pixel) is owned by root and is not
 * writable even from an ADB shell, so an unrooted app cannot touch it. What the platform
 * offers instead is a *user* setting — Pixel calls it Charging optimization > Limit to
 * 80%, other makers use their own name — so all Cellspan can usefully do is point at it.
 *
 * Reading whether it is on is not possible either, and was tried: the Pixel key
 * (`charge_optimization_mode` in `Settings.Secure`) is readable from an ADB shell but
 * returns null to an app, because since Android 11 apps may only read the settings keys
 * the platform marks public. Hence a link and nothing more — the card must not claim a
 * state it cannot observe.
 */
object SystemChargeLimit {

    /**
     * The system Battery screen, where the limit lives. POWER_USAGE_SUMMARY is the
     * documented action and resolves on every phone that ships the Settings app; the
     * exact sub-page differs per manufacturer, so we land the user on Battery and the
     * card's text names what to look for.
     */
    fun settingsIntent(): Intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
}
