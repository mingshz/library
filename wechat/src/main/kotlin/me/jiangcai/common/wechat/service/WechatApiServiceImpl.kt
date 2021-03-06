package me.jiangcai.common.wechat.service

import me.jiangcai.common.wechat.WechatAccountAuthorization
import me.jiangcai.common.wechat.WechatApiService
import me.jiangcai.common.wechat.entity.WechatAccount
import me.jiangcai.common.wechat.entity.WechatUser
import me.jiangcai.common.wechat.entity.WechatUserPK
import me.jiangcai.common.wechat.event.WechatUserUpdatedEvent
import me.jiangcai.common.wechat.repository.WechatAccountRepository
import me.jiangcai.common.wechat.repository.WechatUserRepository
import me.jiangcai.common.wechat.util.WechatApiResponseHandler
import me.jiangcai.common.wechat.util.WechatResponse
import org.apache.commons.logging.LogFactory
import org.apache.http.client.methods.HttpGet
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.codec.Hex
import org.springframework.stereotype.Service
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.spec.InvalidParameterSpecException
import java.time.LocalDateTime
import java.util.*
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * @author CJ
 */
@Service
class WechatApiServiceImpl(
    @Autowired
    internal val environment: Environment,
    @Autowired
    internal val applicationContext: ApplicationContext,
    @Autowired
    private val wechatUserRepository: WechatUserRepository,
    @Autowired
    private val wechatAccountRepository: WechatAccountRepository
) : WechatApiService {

    internal val log = LogFactory.getLog(WechatApiServiceImpl::class.java)

    override fun miniDecryptData(user: WechatUser, encryptedData: String, iv: String): WechatUser {
        val entity = wechatUserRepository.getOne(WechatUserPK(user.appId, user.openId))

        val base64Decoder = Base64.getDecoder()
        val sessionKey = base64Decoder.decode(entity.miniSessionKey)
        val encryptedDataByte = base64Decoder.decode(encryptedData)
        // aes-128-cbc
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")

        try {
            // iv 1
            val params = AlgorithmParameters.getInstance("AES")
            params.init(IvParameterSpec(base64Decoder.decode(iv)))

            // iv 2
//        val params =  IvParameterSpec(base64Decoder.decode(iv))
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), params)

            val rawData = cipher.doFinal(encryptedDataByte)

            val root = WechatApiResponseHandler.objectMapper.readTree(rawData)

            val watermark = root["watermark"] ?: throw  IllegalArgumentException("缺少水印")
            if (watermark["appid"]?.textValue() != entity.appId) {
                throw  IllegalArgumentException("水印错误")
            }

            val response = WechatResponse(
                root
            )
            // 手机号码
            //  "phoneNumber": "13580006666",
            //    "purePhoneNumber": "13580006666",
            //    "countryCode": "86",

            response.getOptionalString("nickName")?.let {
                entity.nickname = it
            }
            response.getOptionalInt("gender")?.let {
                entity.sex = it
            }
            response.getOptionalString("city")?.let {
                entity.city = it
            }
            response.getOptionalString("province")?.let {
                entity.province = it
            }
            response.getOptionalString("avatarUrl")?.let {
                entity.avatarUrl = it
            }
            response.getOptionalString("country")?.let {
                entity.country = it
            }
            response.getOptionalString("unionId")?.let {
                entity.unionId = it
            }
            response.getOptionalString("purePhoneNumber")?.let {
                entity.purePhoneNumber = it
            }

            val u = wechatUserRepository.save(entity)

            applicationContext.publishEvent(WechatUserUpdatedEvent(u))

            return u
        } catch (e: InvalidParameterSpecException) {
            throw IllegalArgumentException(e)
        } catch (e: InvalidKeyException) {
            throw IllegalArgumentException(e)
        } catch (e: BadPaddingException) {
            throw IllegalArgumentException(e)
        } catch (e: IllegalBlockSizeException) {
            throw IllegalArgumentException(e)
        }
    }

    override fun queryUserViaMiniAuthorizationCode(
        authorization: WechatAccountAuthorization,
        code: String
    ): WechatUser {
        if (authorization.miniAppId == null || authorization.miniAppSecret == null)
            throw IllegalStateException("非法的 WechatAccountAuthorization 缺少公众号")

        return environment.newClient().use {
            val rs = it.execute(
                HttpGet("https://api.weixin.qq.com/sns/jscode2session?appid=${authorization.miniAppId}&secret=${authorization.miniAppSecret}&js_code=${code}&grant_type=authorization_code"),
                WechatApiResponseHandler()
            )
//            unionid	string	用户在开放平台的唯一标识符，在满足 UnionID 下发条件的情况下会返回，详见 UnionID 机制说明。
            val openid = rs.getStringOrError("openid")
            val sessionKey = rs.getStringOrError("session_key")

            val usr = wechatUserRepository.findByIdOrNull(
                WechatUserPK(
                    authorization.miniAppId, openid
                )
            ) ?: WechatUser(
                appId = authorization.miniAppId,
                openId = openid
            )
            usr.miniSessionKey = sessionKey
            wechatUserRepository.save(usr)
        }
    }

    override fun queryUserViaAuthorizationCode(authorization: WechatAccountAuthorization, code: String): WechatUser {
        if (authorization.accountAppId == null || authorization.accountAppSecret == null)
            throw IllegalStateException("非法的 WechatAccountAuthorization 缺少公众号")
        return environment.newClient().use { client1 ->
            val rs = client1.execute(
                HttpGet("https://api.weixin.qq.com/sns/oauth2/access_token?appid=${authorization.accountAppId}&secret=${authorization.accountAppSecret}&code=${code}&grant_type=authorization_code"),
                WechatApiResponseHandler()
            )

            val access = rs.getStringOrError("access_token")
//            "refresh_token":"REFRESH_TOKEN",
            val openid = rs.getStringOrError("openid")

            environment.newClient().use {
                val rs2 = it.execute(
                    HttpGet("https://api.weixin.qq.com/sns/userinfo?access_token=${access}&openid=${openid}&lang=zh_CN")
                    , WechatApiResponseHandler()
                )

                // 有则改之，无则加勉
                val user = wechatUserRepository.findByIdOrNull(WechatUserPK(authorization.accountAppId, openid))
                    ?: WechatUser(
                        appId = authorization.accountAppId,
                        openId = openid
                    )

                user.nickname = rs2.getStringOrError("nickname")
                user.sex = rs.getIntOrError("sex")
                user.province = rs.getStringOrError("province")
                user.city = rs.getStringOrError("city")
                user.country = rs.getStringOrError("country")
                user.avatarUrl = rs.getStringOrError("headimgurl")
                user.unionId = rs.getOptionalString("unionid")

                wechatUserRepository.save(user)
            }
        }
    }

    override fun signature(authorization: WechatAccountAuthorization, url: String): Map<String, Any> {
        if (authorization.accountAppId == null || authorization.accountAppSecret == null)
            throw IllegalStateException("非法的 WechatAccountAuthorization 缺少公众号")

        val token = javascriptToken(authorization)

        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonceStr = UUID.randomUUID().toString().replace("-", "")

        log.debug("JS SDK Sign using url:$url")

        val toSign = StringBuilder("jsapi_ticket=")
        toSign.append(token).append("&noncestr=")
        toSign.append(nonceStr).append("&timestamp=")
        toSign.append(timestamp).append("&url=")
        toSign.append(url)

        val toSignBytes = toSign.toString().toByteArray(charset("UTF-8"))
        val messageDigest = MessageDigest.getInstance("sha1")
        messageDigest.update(toSignBytes)
        val signature = String(Hex.encode(messageDigest.digest()))

        return mapOf(
            "appId" to authorization.accountAppId,
            "timestamp" to timestamp,
            "nonceStr" to nonceStr,
            "signature" to signature
        )
    }

    private fun javascriptToken(authorization: WechatAccountAuthorization): String {
        if (authorization.accountAppId == null || authorization.accountAppSecret == null)
            throw IllegalStateException("非法的 WechatAccountAuthorization 缺少公众号")

        val account = wechatAccountRepository.findByIdOrNull(authorization.accountAppId)
            ?: WechatAccount((authorization.accountAppId))

        if (account.javascriptTicket != null && account.javascriptTimeToExpire != null && account.javascriptTimeToExpire!!.isBefore(
                LocalDateTime.now()
            )
        ) {
            return account.javascriptTicket!!
        }

        return accessWithToken(account, authorization.accountAppSecret) { token ->
            val method = HttpGet("https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=${token}&type=jsapi")
            environment.newClient().use {
                val root = it.execute(method, WechatApiResponseHandler())
                val ticket = root.getStringOrError("ticket")
                val seconds = root.getIntOrError("expires_in")
                account.javascriptTicket = ticket
                account.javascriptTimeToExpire = LocalDateTime.now().plusSeconds(seconds.toLong())
                wechatAccountRepository.save(account)
                ticket
            }
        }
//        accessToken(account,wechatAppSecret)
    }

    private fun <T> accessWithToken(account: WechatAccount, wechatAppSecret: String, block: (String) -> T): T {
        return block.invoke(getAccessToken(account, wechatAppSecret))

    }

    private fun getAccessToken(account: WechatAccount, wechatAppSecret: String): String {
        if (account.accessToken != null && account.accessTimeToExpire != null && account.accessTimeToExpire!!.isBefore(
                LocalDateTime.now()
            )
        ) {
            return account.accessToken!!
        }

        return environment.newClient().use {
            val method =
                HttpGet("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${account.appId}&secret=$wechatAppSecret")
            val root = it.execute(method, WechatApiResponseHandler())
            val ticket = root.getStringOrError("access_token")
            val seconds = root.getIntOrError("expires_in")
            account.accessToken = ticket
            account.accessTimeToExpire = LocalDateTime.now().plusSeconds(seconds.toLong())
            wechatAccountRepository.save(account)
            ticket
        }
    }
}
