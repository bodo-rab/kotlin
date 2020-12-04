// !LANGUAGE: +InlineClasses
// !DIAGNOSTICS: -UNUSED_PARAMETER, -PLATFORM_CLASS_MAPPED_TO_KOTLIN
// WITH_RUNTIME

package kotlin.jvm

annotation class JvmInline

inline class IC0(val a: Any): <!VALUE_CLASS_CANNOT_BE_CLONEABLE!>Cloneable<!>

@JvmInline
value class VC0(val a: Any): <!VALUE_CLASS_CANNOT_BE_CLONEABLE!>Cloneable<!>

inline class IC1(val a: Any): <!VALUE_CLASS_CANNOT_BE_CLONEABLE!>java.lang.Cloneable<!>

@JvmInline
value class VC1(val a: Any): <!VALUE_CLASS_CANNOT_BE_CLONEABLE!>java.lang.Cloneable<!>

interface MyCloneable1: Cloneable

inline class IC2(val a: Any): <!VALUE_CLASS_CANNOT_BE_CLONEABLE!>MyCloneable1<!>

@JvmInline
value class VC2(val a: Any): <!VALUE_CLASS_CANNOT_BE_CLONEABLE!>MyCloneable1<!>

interface MyCloneable2: java.lang.Cloneable

inline class IC3(val a: Any): <!VALUE_CLASS_CANNOT_BE_CLONEABLE!>MyCloneable2<!>

@JvmInline
value class VC3(val a: Any): <!VALUE_CLASS_CANNOT_BE_CLONEABLE!>MyCloneable2<!>