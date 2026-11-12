package io.githuh.onehandedmode.hook

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

// getDeclaredField()/declaredMethods clone and scan the whole class on every call, and most
// callers here sit on the input and animation paths. Resolve each class member once.
private val fieldCache = ConcurrentHashMap<String, Field>()
private val methodCache = ConcurrentHashMap<String, Method>()

private fun cachedField(type: Class<*>, name: String): Field =
    fieldCache.getOrPut(type.name + '#' + name) {
        type.getDeclaredField(name).apply { isAccessible = true }
    }

private fun cachedMethod(type: Class<*>, name: String, argCount: Int): Method =
    methodCache.getOrPut(type.name + '#' + name + '/' + argCount) {
        type.declaredMethods.single { it.name == name && it.parameterCount == argCount }
            .apply { isAccessible = true }
    }

internal fun Any.read(name: String): Any? = cachedField(javaClass, name).get(this)

internal fun Any.write(name: String, value: Any) {
    cachedField(javaClass, name).set(this, value)
}

internal fun Any.call(name: String, vararg args: Any?): Any? =
    cachedMethod(javaClass, name, args.size).invoke(this, *args)
