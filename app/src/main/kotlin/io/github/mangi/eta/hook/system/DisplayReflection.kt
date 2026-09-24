package io.github.mangi.eta.hook.system

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Small cached adapter for framework members. An unknown ROM signature fails closed. */
internal object DisplayReflection {
    private val fields = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
    private val methods = ConcurrentHashMap<String, Method>()
    fun field(type: Class<*>, name: String): Field = fields.getOrPut(type to name) {
        generateSequence(type) { it.superclass }.mapNotNull { c ->
            runCatching { c.getDeclaredField(name) }.getOrNull()
        }.first().apply { isAccessible = true }
    }
    fun get(owner: Any, name: String): Any? = field(owner.javaClass, name).get(owner)
    fun set(owner: Any, name: String, value: Any?) = field(owner.javaClass, name).set(owner, value)
    fun call(owner: Any, name: String, vararg args: Any?): Any? {
        val type = if (owner is Class<*>) owner else owner.javaClass
        val key = type.name + "." + name + args.joinToString { it?.javaClass?.name ?: "null" }
        val method = methods.getOrPut(key) {
            generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
                .first { m -> m.name == name && m.parameterCount == args.size &&
                    m.parameterTypes.indices.all { i -> accepts(m.parameterTypes[i], args[i]) } }
                .apply { isAccessible = true }
        }
        return method.invoke(if (owner is Class<*>) null else owner, *args)
    }
    private fun accepts(type: Class<*>, value: Any?): Boolean = when {
        value == null -> !type.isPrimitive
        type == Integer.TYPE -> value is Int
        type == java.lang.Boolean.TYPE -> value is Boolean
        type == java.lang.Long.TYPE -> value is Long
        else -> type.isInstance(value)
    }
}
