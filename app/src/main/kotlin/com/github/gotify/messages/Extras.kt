package com.github.gotify.messages

internal object Extras {
    fun <T> getNestedValue(clazz: Class<T>, extras: Map<String, Any>?, vararg keys: String): T? {
        var value: Any? = extras

        keys.forEach { key ->
            if (value == null) {
                return null
            }

            value = (value as Map<*, *>)[key]
        }

        if (!clazz.isInstance(value)) {
            return null
        }

        return clazz.cast(value)
    }
}
