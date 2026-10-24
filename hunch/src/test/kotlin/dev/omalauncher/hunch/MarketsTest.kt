package dev.omalauncher.hunch
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class MarketsTest {
 @Test fun encodingAndEmbeddedColonsArePreserved() {
  val m=Market.parse(JSONObject("""{"outcomes":[{"outcome":12,"name":"template:price","description":"perp:xyz:LLY|threshold:100","quoteToken":"USDC"}]}""")).single()
  assertEquals("#120",m.coin(0));assertEquals("#121",m.coin(1));assertEquals("xyz:LLY",m.fields["perp"])
 }
 @Test fun emptyBookIsNotFiftyPercent(){assertNull(Book.best(JSONObject("""{"levels":[[],[]]}"""),0))}
 @Test fun zeroSizeIsNotExecutable(){assertNull(Book.best(JSONObject("""{"levels":[[{"px":"0.5","sz":"0"}],[]]}"""),0))}

 @Test fun depthPreviewUsesAllAsksAndRefusesFullEstimateWhenShort() {
  val levels=listOf(Level(java.math.BigDecimal("0.5"),java.math.BigDecimal("10")),Level(java.math.BigDecimal("0.8"),java.math.BigDecimal("10")))
  val q=Book.preview(levels,java.math.BigDecimal("9"));assertTrue(q.complete);assertEquals(0,q.grossPayout.compareTo(java.math.BigDecimal("15")))
  assertFalse(Book.preview(levels,java.math.BigDecimal("20")).complete)
 }
 @Test fun onlyExactTermsGroupAcrossVenues() {
  val m=Market(1,"out","sportsTotal",mapOf("line" to "50"),"USDC")
  assertEquals(1,Market.groups(listOf(m,m.copy(id=2,venue="txyz"))).size)
  assertEquals(2,Market.groups(listOf(m,m.copy(id=2,fields=mapOf("line" to "51")))).size)
  assertEquals(2,Market.groups(listOf(m.copy(fields=emptyMap()),m.copy(id=2,fields=emptyMap()))).size)
 }
 @Test fun inheritedQuestionTermsAreNotDropped() {
  val m=Market.parse(JSONObject("""{"questions":[{"question":1,"description":"participantA:Cranes|participantB:Foxes|countedPlay:regulation","namedOutcomes":[12]}],"outcomes":[{"outcome":12,"name":"template:sportsContestDraw2","description":""}]}""")).single()
  assertTrue(m.title.contains("draw"));assertEquals("regulation",m.fields["countedPlay"])
 }
 @Test fun labelsDoNotExposeTemplateOrVenueCodes() {
  val m=Market(1,"txyz","companyIpoConfirmed",mapOf("company" to "Paper"),"USDC")
  assertEquals("Trade.xyz",m.venueName);assertEquals("Paper: IPO confirmed?",m.title)
 }
}
