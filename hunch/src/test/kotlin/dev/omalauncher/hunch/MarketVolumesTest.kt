package dev.omalauncher.hunch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class MarketVolumesTest {
 private val m=Market(1,"out","binaryPrice",emptyMap(),"USDC")
 @Test fun matchesExactCoinsAndSumsBothSidesNotArrayPosition() {
  val response=JSONArray("""[{},[{"coin":"@10","dayNtlVlm":"999999"},{"coin":"#11","dayNtlVlm":"12.34"},{"coin":"#10","dayNtlVlm":"56.78"}]]""")
  val row=MarketVolumes.read(listOf(m),response,123).getJSONObject(0)
  assertEquals("69.12",row.getString("volume24h"));assertEquals(123,row.getInt("checked"))
 }
 @Test fun missingNegativeAndMalformedVolumesStayUnknown() {
  for(value in listOf("-1","unknown")){
   val response=JSONArray().put(JSONObject()).put(JSONArray().put(JSONObject().put("coin","#10").put("dayNtlVlm","2")).put(JSONObject().put("coin","#11").put("dayNtlVlm",value)))
   assertFalse(MarketVolumes.read(listOf(m),response,123).getJSONObject(0).has("volume24h"))
  }
  assertFalse(MarketVolumes.read(listOf(m),JSONArray("[{},[]]"),123).getJSONObject(0).has("volume24h"))
 }
}
