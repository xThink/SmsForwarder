package com.idormy.sms.forwarder.utils.sender

import android.text.TextUtils
import android.util.Log
import com.google.gson.Gson
import com.idormy.sms.forwarder.database.entity.Rule
import com.idormy.sms.forwarder.entity.MsgInfo
import com.idormy.sms.forwarder.entity.result.TelegramResult
import com.idormy.sms.forwarder.entity.setting.TelegramSetting
import com.idormy.sms.forwarder.utils.SendUtils
import com.idormy.sms.forwarder.utils.SettingUtils
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.cache.model.CacheMode
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xutil.net.NetworkUtils
import okhttp3.Credentials
import okhttp3.Response
import okhttp3.Route
import java.net.*


@Suppress("PrivatePropertyName", "UNUSED_PARAMETER", "unused")
class TelegramUtils private constructor() {
    companion object {

        private val TAG: String = TelegramUtils::class.java.simpleName

        fun sendMsg(
            setting: TelegramSetting,
            msgInfo: MsgInfo,
            rule: Rule?,
            logId: Long?,
        ) {
            val content: String = if (rule != null) {
                msgInfo.getContentForSend(rule.smsTemplate, rule.regexReplace)
            } else {
                msgInfo.getContentForSend(SettingUtils.smsTemplate.toString())
            }

            var requestUrl = if (setting.apiToken.startsWith("http")) {
                setting.apiToken
            } else {
                "https://api.telegram.org/bot" + setting.apiToken + "/sendMessage"
            }
            Log.i(TAG, "requestUrl:$requestUrl")

            val request = if (setting.method != null && setting.method == "GET") {
                requestUrl += "?chat_id=" + setting.chatId + "&text=" + URLEncoder.encode(content, "UTF-8")
                Log.i(TAG, "requestUrl:$requestUrl")
                XHttp.get(requestUrl)
            } else {
                val bodyMap: MutableMap<String, Any> = mutableMapOf()
                bodyMap["chat_id"] = setting.chatId
                bodyMap["text"] = htmlEncode(content)
                bodyMap["parse_mode"] = "HTML"
                bodyMap["disable_web_page_preview"] = "true"
                val requestMsg: String = Gson().toJson(bodyMap)
                Log.i(TAG, "requestMsg:$requestMsg")
                XHttp.post(requestUrl).upJson(requestMsg)
            }

            //设置代理
            if ((setting.proxyType == Proxy.Type.HTTP || setting.proxyType == Proxy.Type.SOCKS)
                && !TextUtils.isEmpty(setting.proxyHost) && !TextUtils.isEmpty(setting.proxyPort)
            ) {
                //代理服务器的IP和端口号
                Log.d(TAG, "proxyHost = ${setting.proxyHost}, proxyPort = ${setting.proxyPort}")
                val proxyHost = if (NetworkUtils.isIP(setting.proxyHost)) setting.proxyHost else NetworkUtils.getDomainAddress(setting.proxyHost)
                if (!NetworkUtils.isIP(proxyHost)) {
                    throw Exception("代理服务器主机名解析失败：proxyHost=$proxyHost")
                }
                val proxyPort: Int = setting.proxyPort?.toInt() ?: 7890

                Log.d(TAG, "proxyHost = $proxyHost, proxyPort = $proxyPort")
                request.okproxy(Proxy(setting.proxyType, InetSocketAddress(proxyHost, proxyPort)))

                //代理的鉴权账号密码
                if (setting.proxyAuthenticator == true
                    && (!TextUtils.isEmpty(setting.proxyUsername) || !TextUtils.isEmpty(setting.proxyPassword))
                ) {
                    Log.i(TAG, "proxyUsername = ${setting.proxyUsername}, proxyPassword = ${setting.proxyPassword}")

                    if (setting.proxyType == Proxy.Type.HTTP) {
                        request.okproxyAuthenticator { _: Route?, response: Response ->
                            //设置代理服务器账号密码
                            val credential = Credentials.basic(setting.proxyUsername.toString(), setting.proxyPassword.toString())
                            response.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                    } else {
                        Authenticator.setDefault(object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication {
                                return PasswordAuthentication(setting.proxyUsername.toString(), setting.proxyPassword?.toCharArray())
                            }
                        })
                    }
                }
            }

            request.keepJson(true)
                .ignoreHttpsCert()
                .timeOut((SettingUtils.requestTimeout * 1000).toLong()) //超时时间10s
                .cacheMode(CacheMode.NO_CACHE)
                .retryCount(SettingUtils.requestRetryTimes) //超时重试的次数
                .retryDelay(SettingUtils.requestDelayTime) //超时重试的延迟时间
                .retryIncreaseDelay(SettingUtils.requestDelayTime) //超时重试叠加延时
                .timeStamp(true)
                .execute(object : SimpleCallBack<String>() {

                    override fun onError(e: ApiException) {
                        Log.e(TAG, e.detailMessage)
                        SendUtils.updateLogs(logId, 0, e.displayMessage)
                    }

                    override fun onSuccess(response: String) {
                        Log.i(TAG, response)

                        val resp = Gson().fromJson(response, TelegramResult::class.java)
                        if (resp.ok == true) {
                            SendUtils.updateLogs(logId, 2, response)
                        } else {
                            SendUtils.updateLogs(logId, 0, response)
                        }
                    }

                })

        }

        fun sendMsg(setting: TelegramSetting, msgInfo: MsgInfo) {
            sendMsg(setting, msgInfo, null, null)
        }

        private fun htmlEncode(source: String?): String {
            if (source == null) {
                return ""
            }
            val buffer = StringBuffer()
            for (element in source) {
                when (element) {
                    '<' -> buffer.append("&lt;")
                    '>' -> buffer.append("&gt;")
                    '&' -> buffer.append("&amp;")
                    '"' -> buffer.append("&quot;")
                    //10, 13 -> buffer.append("\n")
                    else -> buffer.append(element)
                }
            }
            return buffer.toString()
        }
    }
}