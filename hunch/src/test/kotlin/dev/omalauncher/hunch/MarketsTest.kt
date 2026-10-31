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
 @Test fun sourceDatesAreReadableWithoutInventingZone(){
  assertEquals("Oct 30, 2026 · 13:35 (venue time)",MarketDates.display("20261030-1335"))
  assertEquals("20260230-0000",MarketDates.display("20260230-0000"))
 }
 @Test fun inheritedSportFallbackStaysWithItsCompetition() {
  val root=JSONObject("""{"questions":[{"question":4,"description":"sport:Soccer|competition:English Premier League|participantA:Liverpool|participantB:Arsenal","namedOutcomes":[12],"fallbackOutcome":13}],"outcomes":[{"outcome":12,"name":"template:sportsContestParticipant2","description":"participant:Liverpool"},{"outcome":13,"name":"template fallback","description":""}]}""")
  val markets=Market.parse(root)
  assertTrue(markets.all{it.category=="Sports" && it.section=="English Premier League" && it.eventTitle=="Liverpool vs Arsenal"})
 }
 @Test fun assetsAndPreIpoHaveStableDiscoveryGroups() {
  val price=Market(1,"txyz","binaryPrice",mapOf("perp" to "xyz:INTC"),"USDC")
  assertEquals("Prices",price.category);assertEquals("INTC",price.section);assertEquals("Stocks & macro",price.priceType)
  assertEquals("Crypto",price.copy(fields=mapOf("perp" to "BTC")).priceType)
  assertEquals("Pre-IPO",Market(2,"out","template fallback",mapOf("company" to "OpenAI"),"USDC").category)
 }

 @Test fun resolvesSideNamesFromVenueTemplates() {
  val m=Market(3,"out","sportsContestWinner",mapOf("shortNameA" to "HOU","shortNameB" to "OKC"),"USDC",listOf("{shortNameA}","{shortNameB}"))
  assertEquals("HOU",m.sideLabel(0));assertEquals("OKC",m.sideLabel(1))
 }

}
