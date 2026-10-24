package com.roziqrizal.rizqflow.billing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Activity di balik [Context] Compose (yang sering terbungkus ContextWrapper), atau null bila tidak ada. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
