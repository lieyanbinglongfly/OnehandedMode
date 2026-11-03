package io.githuh.onehandedmode.hook

internal fun Any.read(name: String): Any? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

internal fun Any.write(name: String, value: Any) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

internal fun Any.call(name: String, vararg args: Any?): Any? {
    val method = javaClass.declaredMethods.single {
        it.name == name && it.parameterCount == args.size
    }
    method.isAccessible = true
    return method.invoke(this, *args)
}
