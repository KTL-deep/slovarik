package com.slovarik.app

/** Firestore returns integer values as Long, including inside preference maps. */
internal fun canonicalCloudValue(value: Any?): Any? = when (value) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Map<*, *> -> value.entries.associate { (key, child) -> key to canonicalCloudValue(child) }
    is List<*> -> value.map(::canonicalCloudValue)
    else -> value
}
