/*
 * YumaPlayer Desktop (Linux) port shim.
 * Minimal android.annotation.SuppressLint replacement.
 */
package android.annotation

@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.EXPRESSION,
    AnnotationTarget.TYPE,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FILE,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class SuppressedLint(
    vararg val lint: String,
    val repeatSuppress: Boolean = false,
    val toolsOnly: Boolean = false,
)

@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.EXPRESSION,
    AnnotationTarget.TYPE,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FILE,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class SuppressLint(vararg val value: String)

/** Source-compatible duplicate of android.annotation.SuppressLint (same spelling). */
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.EXPRESSION,
    AnnotationTarget.TYPE,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FILE,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class SuppressLint(vararg val value: String)


annotation class TargetApi(vararg val value: kotlin.reflect.KClass<*>)

annotation class CallSuper

annotation class MainThread

annotation class WorkerThread

annotation class UiThread

annotation class AnyThread

annotation class IntDef(
    vararg val value: Long,
    val flag: Boolean = false,
)

annotation class StringDef(vararg val value: String)
