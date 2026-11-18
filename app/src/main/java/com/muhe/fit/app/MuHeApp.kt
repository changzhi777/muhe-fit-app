package com.muhe.fit.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。Hilt 单例容器（依赖白名单裁定 #12）；
 * 后续全局单例（Retrofit/OkHttp/Room DB）在此挂 @Inject 或 Module 提供。
 */
@HiltAndroidApp
class MuHeApp : Application()
