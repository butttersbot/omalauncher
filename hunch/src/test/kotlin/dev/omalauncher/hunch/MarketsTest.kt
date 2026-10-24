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
}
