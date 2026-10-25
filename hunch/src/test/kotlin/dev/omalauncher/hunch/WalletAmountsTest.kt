package dev.omalauncher.hunch

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class WalletAmountsTest {
 @Test fun nativeUsdcSixDecimalsAreExact(){assertEquals("12.5",WalletAmounts.units("0xbebc20",6));assertEquals("0.000001",WalletAmounts.units("0x1",6))}
 @Test fun gasUsesEighteenDecimals(){assertEquals("0.001",WalletAmounts.units("0x38d7ea4c68000",18))}
 @Test fun fullUint256DoesNotOverflow(){assertTrue(WalletAmounts.units("0x"+"f".repeat(64),6).length>70)}
 @Test fun malformedRpcIsNotZero(){for(v in listOf("0x","0x-1","error","0x"+"f".repeat(65))){try{WalletAmounts.units(v,6);fail(v)}catch(_:IllegalArgumentException){}}}
 @Test fun missingUsdcInValidStateIsZero(){assertEquals("0" to "0",WalletAmounts.spot(JSONObject("{balances:[]}")))}
 @Test fun lockedFundsAreNotAvailable(){assertEquals("10.25" to "7.00",WalletAmounts.spot(JSONObject("{balances:[{coin:'USDC',token:0,total:'10.25',hold:'3.25'}]}")))}
 @Test fun invalidHoldIsNotSpendable(){try{WalletAmounts.spot(JSONObject("{balances:[{coin:'USDC',token:0,total:'2',hold:'3'}]}"));fail()}catch(_:IllegalArgumentException){}}
 @Test fun noZeroOrTruncatedReceiveAddress(){assertFalse(WalletAmounts.address("0x"+"0".repeat(40)));assertFalse(WalletAmounts.address("0x123"));assertTrue(WalletAmounts.address("0x"+"1".repeat(40)))}
}
