package dev.omalauncher.hunch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

/** Drafts are inert. Only a fresh native review can turn a draft into an order. */
internal class BetDrafts(context:Context, demo:Boolean) {
    private val prefs=context.getSharedPreferences(if(demo) "bet-drafts-fixture" else "bet-drafts",Context.MODE_PRIVATE)
    fun all():List<JSONObject> {
        val array=JSONArray(prefs.getString("drafts","[]"))
        require(array.length()<=20)
        return (0 until array.length()).map{JSONObject(array.getJSONObject(it).toString())}
    }
    fun upsert(market:Market,side:Int,budget:BigDecimal,quote:JSONObject):List<JSONObject> {
        require(side in 0..1 && budget>BigDecimal.ZERO && budget.scale()<=2 && budget.precision()<=14)
        require(quote.optBoolean("valid") && quote.optBoolean("complete")) { "Refresh the full stake preview first" }
        require(System.currentTimeMillis()-quote.getLong("time") in -5000L..60000L) { "Refresh the stake preview" }
        val rows=all().toMutableList();val key="${market.id}:$side";val index=rows.indexOfFirst{it.getString("key")==key}
        require(index>=0 || rows.size<20){"A slip holds up to 20 separate bets"}
        val previous=rows.getOrNull(index)
        val identical=previous!=null && previous.getString("budget").toBigDecimal().compareTo(budget)==0 && previous.getLong("quotedAt")==quote.getLong("time") && previous.getString("identity")==market.identity
        val row=JSONObject().put("draftId",if(identical)previous!!.getString("draftId") else UUID.randomUUID().toString()).put("key",key)
            .put("marketId",market.id.toString()).put("side",side).put("title",market.title).put("label",market.sideLabel(side)).put("identity",market.identity)
            .put("budget",budget.toPlainString()).put("estimatedPayout",quote.getString("payout").removePrefix("$")).put("quotedAt",quote.getLong("time"))
        if(index>=0)rows[index]=row else rows.add(row)
        save(rows);return rows
    }
    fun remove(ids:Set<String>):List<JSONObject> {val rows=all().filter{it.getString("draftId") !in ids};save(rows);return rows}
    private fun save(rows:List<JSONObject>){check(prefs.edit().putString("drafts",JSONArray(rows).toString()).commit()){ "Could not save your bet slip" }}
}
