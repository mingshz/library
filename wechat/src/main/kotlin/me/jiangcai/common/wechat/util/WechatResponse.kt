package me.jiangcai.common.wechat.util

import com.fasterxml.jackson.databind.JsonNode
import me.jiangcai.common.wechat.exception.WechatApiException

/**
 * @author CJ
 */
data class WechatResponse(
    private val root: JsonNode
) {
    fun getStringOrError(key: String): String {
        val target = root.get(key) ?: throw errorMe()
        return target.textValue()
    }

    fun getIntOrError(key: String): Int {
        val target = root.get(key) ?: throw errorMe()
        return target.intValue()
    }

    private fun errorMe(): Throwable {
        getOptionalString("return_msg")?.let {
            return WechatApiException(message = root.get("return_code").asText() + it, code = 0)
        }
        getOptionalString("err_code_des")?.let {
            return WechatApiException(message = root.get("err_code").asText() + it, code = 0)
        }
        return WechatApiException(message = root.get("errmsg").asText(), code = root.get("errcode").intValue())
    }

    fun getOptionalString(key: String): String? {
        return root.get(key)?.textValue()
    }

    fun getOptionalInt(key: String): Int? {
        return root.get(key)?.intValue()
    }


}