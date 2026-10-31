package dev.omalauncher.hunch
import org.json.JSONObject
/** Debug-only entrypoint selected by MainActivity; fictional data is always visibly labelled. */
object Demo {
 fun request(body:JSONObject):JSONObject = when(body.getString("type")) {
  "outcomeMeta" -> JSONObject("""{"outcomes":[
   {"outcome":1,"name":"template:sportsContestWinner","description":"participantA:Paper Cranes|participantB:Ink Foxes|sport:Fictional|countedPlay:Regulation only|resolutionDeadline:20990101-0000","venue":"out","quoteToken":"USDC","sideSpecs":[{"name":"Cranes"},{"name":"Foxes"}]},
   {"outcome":2,"name":"template:binaryPrice","description":"perp:DEMO|threshold:100|time:20990101-0000","venue":"skew","quoteToken":"USDC"},
   {"outcome":3,"name":"template:companyIpoConfirmed","description":"company:Paper Company|dateTime:20990101-0000","venue":"txyz","quoteToken":"USDC"}] }""")
  "l2Book" -> if(body.getString("coin").startsWith("#2"))JSONObject("""{"levels":[[],[]]}""").put("time",System.currentTimeMillis()).put("coin",body.getString("coin"))
   else JSONObject("""{"levels":[[{"px":"0.39","sz":"100"}],[{"px":"0.41","sz":"100"},{"px":"0.42","sz":"200"}]]}""").put("time",System.currentTimeMillis()).put("coin",body.getString("coin"))
  else -> error("Fixture command unsupported")
 }
 fun volumes()=org.json.JSONArray().put(JSONObject()).put(org.json.JSONArray((1..3).flatMap { id -> (0..1).map { side -> JSONObject().put("coin","#${id*10+side}").put("dayNtlVlm",if(id==2)"0" else if(id==1)"4321.17" else "152.23") } }))

}
