package com.macsans.app.data

import android.content.Context
import com.macsans.app.model.Coupon
import com.macsans.app.model.CouponLeg
import com.macsans.app.model.CouponLegStatus
import com.macsans.app.model.CouponPickType
import com.macsans.app.model.CouponStatus
import org.json.JSONArray
import org.json.JSONObject

object CouponStore {
    private const val PREFS = "muro_coupons"
    private const val KEY = "coupons_json"
    private const val SLIP = "slip_json"

    fun confidenceLabel(percent: Int): String = when {
        percent >= 60 -> "Güçlü tahmin"
        percent >= 48 -> "Orta güven"
        percent >= 38 -> "Zayıf oran / riskli"
        else -> "Çok düşük şans"
    }

    fun loadSlip(context: Context): List<CouponLeg> {
        val raw = prefs(context).getString(SLIP, "[]") ?: "[]"
        return parseLegs(JSONArray(raw))
    }

    fun saveSlip(context: Context, legs: List<CouponLeg>) {
        prefs(context).edit().putString(SLIP, legsToJson(legs).toString()).apply()
    }

    fun clearSlip(context: Context) {
        prefs(context).edit().putString(SLIP, "[]").apply()
    }

    fun addOrReplaceSlipLeg(context: Context, leg: CouponLeg) {
        val current = loadSlip(context).toMutableList()
        val idx = current.indexOfFirst { it.matchId == leg.matchId }
        if (idx >= 0) current[idx] = leg else current.add(leg)
        saveSlip(context, current)
    }

    fun removeSlipLeg(context: Context, matchId: String) {
        saveSlip(context, loadSlip(context).filterNot { it.matchId == matchId })
    }

    fun loadCoupons(context: Context): List<Coupon> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Coupon>()
        for (i in 0 until arr.length()) {
            out += parseCoupon(arr.getJSONObject(i))
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun saveCoupon(context: Context, coupon: Coupon) {
        val list = loadCoupons(context).toMutableList()
        val idx = list.indexOfFirst { it.id == coupon.id }
        if (idx >= 0) list[idx] = coupon else list.add(0, coupon)
        writeCoupons(context, list)
    }

    fun saveAll(context: Context, coupons: List<Coupon>) {
        writeCoupons(context, coupons)
    }

    fun createCouponFromSlip(context: Context): Coupon? {
        val slip = loadSlip(context)
        if (slip.isEmpty()) return null
        val coupon = Coupon(
            id = "c-${System.currentTimeMillis()}",
            createdAt = System.currentTimeMillis(),
            legs = slip.map { it.copy(status = CouponLegStatus.PENDING) },
            status = CouponStatus.OPEN,
            note = "Kombine şans ~%${Coupon(id = "", createdAt = 0, legs = slip).combinedChance}"
        )
        saveCoupon(context, coupon)
        clearSlip(context)
        return coupon
    }

    private fun writeCoupons(context: Context, list: List<Coupon>) {
        val arr = JSONArray()
        list.forEach { arr.put(couponToJson(it)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun legsToJson(legs: List<CouponLeg>): JSONArray {
        val arr = JSONArray()
        legs.forEach { arr.put(legToJson(it)) }
        return arr
    }

    private fun legToJson(leg: CouponLeg) = JSONObject().apply {
        put("matchId", leg.matchId)
        put("homeTeam", leg.homeTeam)
        put("awayTeam", leg.awayTeam)
        put("league", leg.league)
        put("kickoffLabel", leg.kickoffLabel)
        put("pick", leg.pick.name)
        put("predictedPercent", leg.predictedPercent)
        put("confidenceLabel", leg.confidenceLabel)
        put("homeWinPercent", leg.homeWinPercent)
        put("drawPercent", leg.drawPercent)
        put("awayWinPercent", leg.awayWinPercent)
        put("status", leg.status.name)
        put("actualResult", leg.actualResult)
        put("finalScore", leg.finalScore)
    }

    private fun couponToJson(c: Coupon) = JSONObject().apply {
        put("id", c.id)
        put("createdAt", c.createdAt)
        put("status", c.status.name)
        put("note", c.note)
        put("legs", legsToJson(c.legs))
    }

    private fun parseLegs(arr: JSONArray): List<CouponLeg> {
        val out = mutableListOf<CouponLeg>()
        for (i in 0 until arr.length()) {
            out += parseLeg(arr.getJSONObject(i))
        }
        return out
    }

    private fun parseLeg(o: JSONObject) = CouponLeg(
        matchId = o.optString("matchId"),
        homeTeam = o.optString("homeTeam"),
        awayTeam = o.optString("awayTeam"),
        league = o.optString("league"),
        kickoffLabel = o.optString("kickoffLabel"),
        pick = runCatching { CouponPickType.valueOf(o.optString("pick", "HOME")) }
            .getOrDefault(CouponPickType.HOME),
        predictedPercent = o.optInt("predictedPercent"),
        confidenceLabel = o.optString("confidenceLabel"),
        homeWinPercent = o.optInt("homeWinPercent"),
        drawPercent = o.optInt("drawPercent"),
        awayWinPercent = o.optInt("awayWinPercent"),
        status = runCatching { CouponLegStatus.valueOf(o.optString("status", "PENDING")) }
            .getOrDefault(CouponLegStatus.PENDING),
        actualResult = o.optString("actualResult"),
        finalScore = o.optString("finalScore")
    )

    private fun parseCoupon(o: JSONObject) = Coupon(
        id = o.optString("id"),
        createdAt = o.optLong("createdAt"),
        legs = parseLegs(o.optJSONArray("legs") ?: JSONArray()),
        status = runCatching { CouponStatus.valueOf(o.optString("status", "OPEN")) }
            .getOrDefault(CouponStatus.OPEN),
        note = o.optString("note")
    )
}
