package com.blue.xrouter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blue.xrouter.tools.Logger

/**
 * router manager
 * Created by blue on 2018/9/28.
 */
object XRouter {

    private val TAG = "XRouter"

    private val pagesMapping by lazy { mutableMapOf<String, Class<out Activity>>() }

    private val syncMethodMapping by lazy { mutableMapOf<String, XRouterSyncMethod>() }
    private val asyncMethodMapping by lazy { mutableMapOf<String, XRouterAsyncMethod>() }

    private val interceptorMap by lazy { mutableMapOf<Int, XRouterInterceptor>() }
    private val interceptorPriorityList by lazy { mutableListOf<Int>() }
    private var interceptorIndex = 0

    @JvmStatic
    fun init(context: Context, isDebug: Boolean = false) {
        Logger.setDebug(isDebug)
        Logger.d(TAG, "XRouter init")
        XRouterAppInit.init()
        interceptorMap.values.forEach { it.onInit(context) }
    }

    fun registerPage(pageName: String, routerPage: Class<out Activity>) {
        Logger.d(TAG, "registerPage:$pageName")
        pagesMapping.put(pageName, routerPage)
    }

    fun registerSyncMethod(methodName: String, routerMethod: XRouterSyncMethod) {
        Logger.d(TAG, "registerSyncMethod:$methodName")
        syncMethodMapping.put(methodName, routerMethod)
    }

    fun registerAsyncMethod(methodName: String, routerMethod: XRouterAsyncMethod) {
        Logger.d(TAG, "registerAsyncMethod:$methodName")
        asyncMethodMapping.put(methodName, routerMethod)
    }

    fun registerInterceptor(priority: Int, routerInterceptor: XRouterInterceptor) {
        Logger.d(TAG, "registerInterceptor:${routerInterceptor.javaClass.canonicalName}(priority=$priority)")
        interceptorMap.put(priority, routerInterceptor)

        interceptorPriorityList.clear()
        interceptorPriorityList.addAll(interceptorMap.keys.sortedDescending())
    }

    /**
     * recommend
     */
    @JvmStatic
    fun with(context: Context) = XRouterConfig(context)

    /**
     * page route
     */
    fun jump(routerConfig: XRouterConfig, routerCallback: XRouterCallback? = null) {
        Logger.d(TAG, "--- start page route ---")
        Logger.d(TAG, "routerConfig:$routerConfig")
        if (interceptorMap.isNotEmpty()) {
            interceptorIndex = 0
            invokeIntercept(routerConfig, routerCallback)
        } else {
            Logger.d(TAG, "interceptorMap is empty")
            invokeJump(routerConfig, routerCallback)
        }
    }

    fun invokeIntercept(routerConfig: XRouterConfig, routerCallback: XRouterCallback? = null) {
        interceptorMap[interceptorPriorityList[interceptorIndex]]?.let {
            Logger.d(TAG, "invoke intercept:${it.javaClass.canonicalName}(priority=${interceptorPriorityList[interceptorIndex]})")
            it.onProcess(object : XRouterInterceptorCallback {
                override fun onContinue() {
                    Logger.d(TAG, "onContinue")
                    if (interceptorIndex == interceptorMap.size - 1) {
                        invokeJump(routerConfig, routerCallback)
                    } else {
                        interceptorIndex++
                        invokeIntercept(routerConfig, routerCallback)
                    }
                }

                override fun onIntercept(msg: String) {
                    Logger.w(TAG, "onIntercept:$msg")
                    routerCallback?.onRouterError(XRouterResult.Builder().build())
                }

            })
        } ?: routerCallback?.onRouterError(XRouterResult.Builder().build())
    }

    fun invokeJump(routerConfig: XRouterConfig, routerCallback: XRouterCallback? = null) {
        Logger.d(TAG, "invoke jump")
        if (routerConfig.getTarget().isNotBlank()) {
            // get only scheme+authority+path
            var page = routerConfig.getTarget()
            if (routerConfig.getTarget().contains("?")) {
                page = routerConfig.getTarget().split("[?]".toRegex()).toTypedArray()[0]
            } else if (routerConfig.getTarget().contains("#")) {
                page = routerConfig.getTarget().split("[#]".toRegex()).toTypedArray()[0]
            }
            Logger.d(TAG, "page:$page")
            if (pagesMapping.containsKey(page)) {
                Logger.d(TAG, "find page success")
                val intent = Intent().apply {
                    setClass(routerConfig.context, pagesMapping[page])
                    if (routerConfig.getIntentFlags() != -1) {
                        flags = routerConfig.getIntentFlags()
                    }
                    if (routerConfig.context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (page != routerConfig.getTarget()) {
                        data = Uri.parse(routerConfig.getTarget())
                    }
                    putExtras(routerConfig.getData())
                }
                if (routerConfig.context is Activity) {
                    if (routerConfig.getRequestCode() != -1) {
                        Logger.d(TAG, "activity startActivityForResult")
                        routerConfig.context.startActivityForResult(intent, routerConfig.getRequestCode())
                    } else {
                        Logger.d(TAG, "activity startActivity")
                        routerConfig.context.startActivity(intent)
                    }
                    if (routerConfig.getEnterAnim() != -1 && routerConfig.getExitAnim() != -1) {
                        Logger.d(TAG, "overridePendingTransition")
                        routerConfig.context.overridePendingTransition(routerConfig.getEnterAnim(), routerConfig.getExitAnim())
                    }
                    routerCallback?.onRouterSuccess(XRouterResult.Builder().build())
                } else {
                    Logger.d(TAG, "context startActivity")
                    routerConfig.context.startActivity(intent)
                    routerCallback?.onRouterSuccess(XRouterResult.Builder().build())
                }
            } else {
                Logger.d(TAG, "find page error")
                routerCallback?.onRouterError(XRouterResult.Builder().build())
            }
        } else {
            Logger.d(TAG, "target is blank")
            routerCallback?.onRouterError(XRouterResult.Builder().build())
        }
    }

    /**
     * method async route
     */
    fun call(routerConfig: XRouterConfig, routerCallback: XRouterCallback? = null) {
        Logger.d(TAG, "--- start method async route ---")
        Logger.d(TAG, "routerConfig:$routerConfig")
        val targetService = asyncMethodMapping[routerConfig.getTarget()]
        targetService?.let {
            Logger.d(TAG, "find method success")
            it.invoke(routerConfig.context, XRouterParams(routerConfig.getData(), routerConfig.getObj()), routerCallback)
        } ?: let {
            Logger.d(TAG, "find method error")
            routerCallback?.onRouterError(XRouterResult.Builder().build())
        }
    }

    /**
     * method sync route
     */
    fun get(routerConfig: XRouterConfig): XRouterResult {
        Logger.d(TAG, "--- start method sync route ---")
        Logger.d(TAG, "routerConfig:$routerConfig")
        val targetService = syncMethodMapping[routerConfig.getTarget()]
        targetService?.let {
            Logger.d(TAG, "find method success")
            val routerResult = it.invoke(routerConfig.context, XRouterParams(routerConfig.getData(), routerConfig.getObj()))
            Logger.d(TAG, "routerResult:$routerResult")
            return routerResult
        } ?: let {
            Logger.d(TAG, "find method error")
            return XRouterResult.Builder().build()
        }
    }

    @JvmStatic
    fun containsPage(target: String): Boolean {
        var page = target
        if (target.contains("?")) {
            page = target.split("[?]".toRegex()).toTypedArray()[0]
        } else if (target.contains("#")) {
            page = target.split("[#]".toRegex()).toTypedArray()[0]
        }
        return pagesMapping.containsKey(page)
    }

    @JvmStatic
    fun containsMethod(target: String) = !(!syncMethodMapping.containsKey(target) && !asyncMethodMapping.containsKey(target))
}