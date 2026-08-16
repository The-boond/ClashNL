package io.nekohasekai.sfa.account.network

import io.nekohasekai.sfa.account.model.BillingPeriod
import io.nekohasekai.sfa.account.model.CheckoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XBoardAccountJsonParserTest {
    @Test
    fun `login parses bearer authorization`() {
        val session = XBoardAccountJsonParser.parseLogin(
            email = "user@example.com",
            body = """{"status":"success","message":"ok","data":{"auth_data":"Bearer TOKEN","token":"SUB_TOKEN"}}""",
        )

        assertEquals("user@example.com", session.email)
        assertEquals("Bearer TOKEN", session.authorization)
    }

    @Test
    fun `login rejects failed envelope without exposing fields`() {
        val error = assertThrows(AccountApiException::class.java) {
            XBoardAccountJsonParser.parseLogin(
                email = "user@example.com",
                body = """{"status":"fail","message":"账号或密码错误","data":null}""",
            )
        }

        assertEquals("账号或密码错误", error.message)
    }

    @Test
    fun `subscription accepts numeric strings and maps plan`() {
        val details = XBoardAccountJsonParser.parseSubscription(
            """
            {
              "status":"success",
              "message":"ok",
              "data":{
                "email":"user@example.com",
                "u":"100",
                "d":200,
                "transfer_enable":"1000",
                "expired_at":"2000000000",
                "next_reset_at":2000000100,
                "subscribe_url":"https://example.com/s/TOKEN",
                "plan":{
                  "id":3,
                  "name":"标准版",
                  "transfer_enable":1000,
                  "device_limit":"4",
                  "speed_limit":50
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("user@example.com", details.email)
        assertEquals(300L, details.usedBytes)
        assertEquals(1000L, details.transferLimitBytes)
        assertEquals("标准版", details.plan?.name)
        assertEquals(4, details.plan?.deviceLimit)
        assertEquals(50, details.plan?.speedLimitMbps)
        assertFalse(details.isExpired(1_900_000_000L))
        assertTrue(details.isExpired(2_000_000_001L))
    }

    @Test
    fun `subscription rejects malformed response`() {
        assertThrows(AccountApiException::class.java) {
            XBoardAccountJsonParser.parseSubscription("<html>error</html>")
        }
    }

    @Test
    fun `zero device and speed limits map to unlimited`() {
        val details = XBoardAccountJsonParser.parseSubscription(
            """{"status":"success","data":{"email":"user@example.com","plan":{"id":1,"name":"不限量","device_limit":0,"speed_limit":"0"}}}""",
        )

        assertEquals(null, details.plan?.deviceLimit)
        assertEquals(null, details.plan?.speedLimitMbps)
    }

    @Test
    fun `base URL policy requires clean HTTPS origin`() {
        assertEquals(
            "https://cjjc.qzz.io",
            XBoardAccountApi.validateBaseUrl("https://cjjc.qzz.io/"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            XBoardAccountApi.validateBaseUrl("http://cjjc.qzz.io")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XBoardAccountApi.validateBaseUrl("https://user@cjjc.qzz.io")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XBoardAccountApi.validateBaseUrl("https://cjjc.qzz.io?token=value")
        }
    }

    @Test
    fun `plans map available periods flags and limits`() {
        val plans = XBoardAccountJsonParser.parsePlans(
            """
            {"status":"success","data":[{
              "id":7,"name":"旗舰版","content":"高速套餐","month_price":1200,
              "quarter_price":"3000","year_price":null,"transfer_enable":107374182400,
              "device_limit":"5","speed_limit":200,"sold_out":0,
              "purchase_available":true,"renew":"1"
            }]}
            """.trimIndent(),
        )

        assertEquals(1, plans.size)
        assertEquals("旗舰版", plans.single().name)
        assertEquals(2, plans.single().prices.size)
        assertEquals(BillingPeriod.MONTH, plans.single().prices.first().period)
        assertEquals(1200L, plans.single().prices.first().priceCents)
        assertTrue(plans.single().purchaseAvailable)
        assertTrue(plans.single().renewAvailable)
        assertFalse(plans.single().soldOut)
    }

    @Test
    fun `payment methods accept decimal fees`() {
        val methods = XBoardAccountJsonParser.parsePaymentMethods(
            """{"status":"success","data":[{"id":9,"name":"微信支付","payment":"EPay","handling_fee_fixed":30,"handling_fee_percent":"1.20"}]}""",
        )

        assertEquals(9L, methods.single().id)
        assertEquals("EPay", methods.single().provider)
        assertEquals(30L, methods.single().handlingFeeFixedCents)
        assertEquals(1.2, methods.single().handlingFeePercent, 0.001)
    }

    @Test
    fun `orders and trade number parse for pending recovery`() {
        assertEquals(
            "TRADE_123",
            XBoardAccountJsonParser.parseTradeNo("""{"status":"success","data":"TRADE_123"}"""),
        )
        val orders = XBoardAccountJsonParser.parseOrders(
            """
            {"status":"success","data":[{
              "trade_no":"TRADE_123","plan_id":7,"period":"month_price",
              "total_amount":1200,"handling_amount":"15","status":0,"created_at":2000000000,
              "plan":{"id":7,"name":"旗舰版"}
            }]}
            """.trimIndent(),
        )

        assertEquals("TRADE_123", orders.single().tradeNo)
        assertEquals("旗舰版", orders.single().planName)
        assertEquals(BillingPeriod.MONTH, orders.single().period)
        assertEquals(15L, orders.single().handlingAmountCents)
    }

    @Test
    fun `checkout supports url qr and completed results`() {
        val url = XBoardAccountJsonParser.parseCheckout(
            """{"type":1,"data":"https://pay.example.com/cashier"}""",
        )
        val qr = XBoardAccountJsonParser.parseCheckout(
            """{"type":0,"data":"PAYMENT_QR_PAYLOAD"}""",
        )
        val completed = XBoardAccountJsonParser.parseCheckout(
            """{"type":-1,"data":true}""",
        )

        assertTrue(url.isUrl)
        assertTrue(qr.isQrCode)
        assertTrue(completed.completedWithoutGateway)
        assertEquals(CheckoutResult.TYPE_URL, url.type)
    }

    @Test
    fun `error message parser reads failed envelope`() {
        assertEquals(
            "存在待支付订单",
            XBoardAccountJsonParser.parseErrorMessage(
                """{"status":"fail","message":"存在待支付订单","data":null}""",
            ),
        )
    }
}
