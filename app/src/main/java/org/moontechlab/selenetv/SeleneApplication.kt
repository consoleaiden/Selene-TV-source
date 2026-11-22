package org.moontechlab.selenetv

import android.app.Application
import android.util.Log
import coil.EventListener
import org.moontechlab.selenetv.data.local.LocalStore
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.server.RemoteControlServer
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.service.SubscriptionService
import org.moontechlab.selenetv.ui.MoonToast

/**
 * 自定义 Application 类，用于配置 Coil 图片加载
 */
class SeleneApplication : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 初始化 AppDataService
        AppDataService.init(this)
        LocalStore.init(this)

        Log.d("SeleneApplication", "Application initialized")
        
        // 启动遥控器 Web 服务器
        startRemoteControlServer()

        // 本地模式启动后台刷新订阅：不阻塞 onCreate，失败仅 toast
        if (AppDataService.isLocalModeSync()) {
            applicationScope.launch {
                val ok = SubscriptionService.refreshAndPersist()
                if (!ok) withContext(Dispatchers.Main) { MoonToast.show("订阅刷新失败") }
            }
        } else {
            // 服务端模式启动后台同步 18+ 源站标记（供历史/收藏/搜索过滤使用，失败静默）
            org.moontechlab.selenetv.service.AdultSourceFilter.refreshAsync()
        }
    }
    
    private fun startRemoteControlServer() {
        thread {
            if (RemoteControlServer.start()) {
                Log.i("SeleneApplication", "Remote control available at http://<device-ip>:${RemoteControlServer.getPort()}/remote_control")
            }
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        RemoteControlServer.stop()
    }
    
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                createUnsafeOkHttpClient()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // 低端机（armv7a / 2GB）优化：海报/剧照都是不透明图，用 RGB_565（2 字节/像素）替代
            // 默认 ARGB_8888（4 字节/像素），位图内存腰斩、解码更快、GC 抖动更少；画质几乎无损。
            .allowRgb565(true)
            .crossfade(true)
            .eventListener(ImageEventListener())
            .build()
    }
    
    /**
     * 图片代理拦截器
     * 启用且配置了代理地址后，封面图 URL 优先改写为代理地址；
     * 代理加载失败（HTTP 非 2xx / 连接异常）自动回退原始地址重试一次。
     * 未启用 / 未配置 / 非 http(s) 原图 / 原图本身就是代理服务器 时直通，零改动。
     */
    private class ImageProxyInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val data = original.url.toString()

            val proxiedUrl = ImageProxy.buildProxiedUrl(data) ?: return chain.proceed(original)
            Log.d("ImageProxy", "proxy in use: $proxiedUrl")

            val proxiedRequest = original.newBuilder()
                .url(proxiedUrl)
                .build()
            return try {
                val response = chain.proceed(proxiedRequest)
                if (response.isSuccessful) {
                    response
                } else {
                    Log.w("ImageProxy", "proxy HTTP ${response.code}, fallback to original")
                    response.close()
                    chain.proceed(original)   // 代理失败 → 回退原图
                }
            } catch (e: Exception) {
                Log.w("ImageProxy", "proxy failed (${e.message}), fallback to original")
                chain.proceed(original)       // 代理网络异常 → 回退原图
            }
        }
    }

    /**
     * 图片代理地址组装。
     * - 配置含 {url} 占位符：替换为 URL 编码后的原图地址（如 https://images.weserv.nl/?url={url}）
     * - 否则视为前缀：直接拼接 前缀 + 原图地址
     */
    object ImageProxy {
        fun buildProxiedUrl(originalUrl: String): String? {
            if (!AppDataService.isImageProxyEnabledSync()) return null
            val template = AppDataService.getImageProxyUrlSync()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: return null
            if (!originalUrl.startsWith("http://") && !originalUrl.startsWith("https://")) return null
            // 防自环：原图已在代理服务器上时不再包一层
            val proxyHost = template.toHttpUrlOrNull()?.host
            val originalHost = originalUrl.toHttpUrlOrNull()?.host
            if (proxyHost != null && proxyHost == originalHost) return null
            return if (template.contains("{url}")) {
                template.replace("{url}", java.net.URLEncoder.encode(originalUrl, "UTF-8"))
            } else {
                template + originalUrl
            }
        }
    }

    /**
     * 创建信任所有证书的 OkHttpClient
     * 用于解决某些 CDN 证书不被信任的问题
     */
    private fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS) // 走代理链路更长，放宽整体上限
                .addInterceptor(ImageProxyInterceptor())
                .addInterceptor(ImageHeaderInterceptor())
                .addInterceptor(ImageLoggingInterceptor())
                .build()
        } catch (e: Exception) {
            Log.e("SeleneApplication", "Failed to create unsafe OkHttpClient", e)
            return OkHttpClient.Builder()
                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor(ImageProxyInterceptor())
                .addInterceptor(ImageHeaderInterceptor())
                .addInterceptor(ImageLoggingInterceptor())
                .build()
        }
    }
    
    /**
     * 图片请求头拦截器
     * - 默认注入 PC UA + 豆瓣 Referer，绕过豆瓣防盗链
     * - 若请求 host 命中已登录的自托管服务（/api/image-proxy 等），追加 Cookie 用于鉴权
     */
    private class ImageHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val builder = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .header("Referer", "https://movie.douban.com/")

            val serverHost = AppDataService.getServerUrlSync()
                ?.toHttpUrlOrNull()
                ?.host
            if (serverHost != null && originalRequest.url.host == serverHost) {
                AppDataService.getCookiesSync()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { builder.header("Cookie", it) }
            }

            return chain.proceed(builder.build())
        }
    }
    
    /**
     * 图片加载日志拦截器
     * 记录失败的图片请求
     */
    private class ImageLoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            
            return response
        }
    }
    
    /**
     * 图片事件监听器
     * 记录图片解码失败等错误
     */
    private class ImageEventListener : EventListener {
        override fun onError(request: ImageRequest, result: ErrorResult) {
            Log.w("CoilImg", "图片加载失败 data=${request.data} cause=${result.throwable}")
        }
    }
}
